package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.TOOL_WINDOW_ID
import be.jensdescamps.tempotracker.api.ApiException
import be.jensdescamps.tempotracker.api.JiraService
import be.jensdescamps.tempotracker.api.TempoClient
import be.jensdescamps.tempotracker.api.WorklogPayload
import be.jensdescamps.tempotracker.settings.SubmitMode
import be.jensdescamps.tempotracker.settings.TempoSecrets
import be.jensdescamps.tempotracker.settings.TempoSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class SubmitService {

    class Result {
        var created = 0
        var updated = 0
        var deleted = 0
        val errors = mutableListOf<String>()
        val changes get() = created + updated + deleted

        fun summary(): String {
            val parts = buildList {
                if (created > 0) add("$created logged")
                if (updated > 0) add("$updated updated")
                if (deleted > 0) add("$deleted removed")
                if (errors.isNotEmpty()) add("${errors.size} failed")
            }
            val head = if (parts.isEmpty()) "Nothing to submit" else "Tempo: " + parts.joinToString(", ")
            return if (errors.isEmpty()) head else head + "<br>" + errors.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
        }
    }

    private val autoRunning = AtomicBoolean(false)

    @Volatile
    private var catchUpDone = false

    fun pending(dates: Collection<LocalDate>): List<TimeEntry> {
        val settings = TempoSettings.getInstance().state
        val store = TimeStore.getInstance()
        return dates.flatMap { date ->
            val day = store.entries(date)
            val plan = LogPolicy.plan(day, settings)
            day.filter { LogPolicy.pendingAction(it, settings, plan[it.id]) != LogPolicy.Action.NONE }
        }
    }

    /** Creates, updates or deletes Tempo worklogs so they match the local entries of [dates]. */
    @Synchronized
    fun submit(dates: Collection<LocalDate>, indicator: ProgressIndicator? = null): Result {
        val result = Result()
        val todo = pending(dates)
        if (todo.isEmpty()) return result

        val settings = TempoSettings.getInstance().state
        val store = TimeStore.getInstance()
        val jira = JiraService.getInstance()
        val tempo = TempoClient(TempoSecrets.tempoToken)
        val me = jira.myself()
        val plans = dates.associate { it.toString() to LogPolicy.plan(store.entries(it), settings) }

        todo.forEachIndexed { index, entry ->
            indicator?.checkCanceled()
            indicator?.fraction = index.toDouble() / todo.size
            indicator?.text = "Logging ${entry.ticket} (${entry.date})"
            try {
                if (LogPolicy.pendingAction(entry, settings) == LogPolicy.Action.DELETE) {
                    tempo.delete(entry.worklogId)
                    store.update(entry.id) {
                        it.worklogId = 0
                        it.submittedSeconds = 0
                        it.submittedDescription = ""
                        it.submittedStartTime = ""
                        it.lastError = ""
                    }
                    result.deleted++
                    return@forEachIndexed
                }
                val issue = jira.issue(entry.ticket)
                val withSummary = entry.copy(summary = issue.summary)
                val seconds = LogPolicy.secondsToLog(withSummary, settings)
                val description = LogPolicy.description(withSummary, settings)
                val start = plans[entry.date]?.get(entry.id) ?: LogPolicy.startTime(entry, settings)
                val payload = WorklogPayload(
                    issueId = issue.id,
                    authorAccountId = me.accountId,
                    timeSpentSeconds = seconds,
                    startDate = entry.date,
                    startTime = start,
                    description = description,
                )
                val worklogId = if (entry.worklogId != 0L) {
                    try {
                        tempo.update(entry.worklogId, payload).also { result.updated++ }
                    } catch (e: ApiException) {
                        if (e.status != 404) throw e
                        tempo.create(payload).also { result.created++ } // deleted in Tempo meanwhile
                    }
                } else {
                    tempo.create(payload).also { result.created++ }
                }
                store.update(entry.id) {
                    it.summary = issue.summary
                    it.worklogId = worklogId
                    it.submittedSeconds = seconds
                    it.submittedDescription = description
                    it.submittedStartTime = start
                    it.lastError = ""
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                store.update(entry.id) { it.lastError = message }
                result.errors += "${entry.ticket.ifBlank { "?" }} (${entry.date}): $message"
            }
        }
        return result
    }

    /** Fetches issue summaries for [date] and returns the Tempo total for that day (null if unavailable). */
    fun refreshDay(date: LocalDate): Long? {
        val store = TimeStore.getInstance()
        val jira = JiraService.getInstance()
        for (entry in store.entries(date)) {
            if (entry.ticket.isBlank()) continue
            try {
                val issue = jira.issue(entry.ticket)
                store.update(entry.id) {
                    it.summary = issue.summary
                    if (it.lastError.contains("doesn't exist")) it.lastError = ""
                }
            } catch (e: ApiException) {
                if (e.status == 404) store.update(entry.id) { it.lastError = e.message.orEmpty() }
                else return null // not configured / offline: no point trying the rest
            }
        }
        return try {
            TempoClient(TempoSecrets.tempoToken).totalSeconds(jira.myself().accountId, date)
        } catch (e: ApiException) {
            null
        }
    }

    /** Called by the tracker every tick: daily reminder or auto-submit. */
    fun onTick(project: Project?) {
        val settings = TempoSettings.getInstance()
        val store = TimeStore.getInstance()
        val today = LocalDate.now()
        val dates = sortedSetOf<LocalDate>()

        if (settings.mode == SubmitMode.DAILY && !catchUpDone) {
            catchUpDone = true
            (1L..7L).forEach { dates += today.minusDays(it) } // anything left over from previous days
        }

        val due = Durations.parseClock(settings.state.dailyTime)
        if (due != null && !LocalTime.now().isBefore(due) && store.lastDailyRun != today.toString()) {
            store.lastDailyRun = today.toString()
            when (settings.mode) {
                SubmitMode.DAILY -> (0L..7L).forEach { dates += today.minusDays(it) }
                SubmitMode.MANUAL -> remind(project, today)
            }
        }

        if (dates.isNotEmpty()) autoSubmit(project, dates)
    }

    private fun remind(project: Project?, today: LocalDate) {
        val pending = pending(listOf(today)).filter { it.ticket.isNotBlank() }
        if (pending.isEmpty()) return
        val settings = TempoSettings.getInstance().state
        val total = pending.sumOf { LogPolicy.secondsToLog(it, settings) }
        notify(
            project,
            "${Durations.format(total)} on ${pending.size} ticket(s) isn't in Tempo yet.",
            NotificationType.INFORMATION,
            openToolWindow = true,
        )
    }

    private fun autoSubmit(project: Project?, dates: Collection<LocalDate>) {
        if (pending(dates).isEmpty()) return
        if (!autoRunning.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = submit(dates)
                if (result.changes > 0 || result.errors.isNotEmpty()) {
                    val type = if (result.errors.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
                    notify(project, result.summary(), type, openToolWindow = result.errors.isNotEmpty())
                }
            } catch (e: Exception) {
                notify(project, "Auto-submit failed: ${StringUtil.escapeXmlEntities(e.message.orEmpty())}", NotificationType.ERROR, true)
            } finally {
                autoRunning.set(false)
            }
        }
    }

    companion object {
        fun getInstance(): SubmitService = service()

        fun notify(project: Project?, message: String, type: NotificationType, openToolWindow: Boolean = false) {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Tempo Tracker")
                .createNotification("Tempo Tracker", message, type)
            if (openToolWindow && project != null) {
                notification.addAction(NotificationAction.createSimpleExpiring("Review…") {
                    if (!project.isDisposed) ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
                })
            }
            notification.notify(project)
        }
    }
}
