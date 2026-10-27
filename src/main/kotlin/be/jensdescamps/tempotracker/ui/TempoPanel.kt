package be.jensdescamps.tempotracker.ui

import be.jensdescamps.tempotracker.core.ActivityTracker
import be.jensdescamps.tempotracker.core.BranchResolver
import be.jensdescamps.tempotracker.core.Durations
import be.jensdescamps.tempotracker.core.LogPolicy
import be.jensdescamps.tempotracker.core.SubmitService
import be.jensdescamps.tempotracker.core.TimeStore
import be.jensdescamps.tempotracker.core.TimeStoreListener
import be.jensdescamps.tempotracker.settings.TempoConfigurable
import be.jensdescamps.tempotracker.settings.TempoSettings
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.Icon
import javax.swing.JPanel

class TempoPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private var date: LocalDate = LocalDate.now()
    private var tempoTotal: Long? = null
    private val model = EntryTableModel()
    private val table = JBTable(model)
    private val header = JBLabel()
    private val trackingLabel = JBLabel()

    init {
        table.setShowGrid(false)
        table.emptyText.text = "No time tracked on this day"
        model.applyColumnWidths(table)

        val top = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(6, 8)
            add(header, BorderLayout.NORTH)
            add(trackingLabel, BorderLayout.SOUTH)
        }
        val content = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        }
        setContent(content)

        val toolbar = ActionManager.getInstance().createActionToolbar("TempoTrackerToolbar", createActions(), true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        ApplicationManager.getApplication().messageBus.connect(parent)
            .subscribe(TimeStoreListener.TOPIC, TimeStoreListener {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed && !table.isEditing) reload()
                }
            })

        reload()
        refreshRemote()
    }

    private fun reload() {
        val settings = TempoSettings.getInstance().state
        val selectedId = model.entryAt(table.selectedRow)?.id
        val entries = TimeStore.getInstance().entries(date)
            .sortedWith(compareBy({ it.ticket.isBlank() }, { it.startTime }))
        model.setEntries(entries, settings)
        selectedId?.let { model.indexOf(it) }?.takeIf { it >= 0 }?.let { table.selectionModel.setSelectionInterval(it, it) }

        val tracked = entries.sumOf { it.trackedSeconds }
        val toLog = entries.sumOf { LogPolicy.secondsToLog(it, settings) }
        val inTempo = tempoTotal?.let { " · in Tempo ${Durations.format(it)}" }.orEmpty()
        header.text = "<html><b>${date.format(DAY_FORMAT)}</b>&nbsp;&nbsp;tracked ${Durations.format(tracked)} · " +
            "to log ${Durations.format(toLog)}$inTempo</html>"
        trackingLabel.text = trackingText()
    }

    private fun trackingText(): String {
        val tracker = ActivityTracker.getInstance()
        val info = BranchResolver.resolve(project)
        return when {
            tracker.isPaused -> "⏸ Tracking paused"
            info.branch == null -> "No Git branch detected"
            info.ticket == null -> "Branch ${info.branch} has no ticket: Tools → Tempo Tracker → Assign Jira Ticket"
            tracker.isTrackingProject(project) -> "● Tracking ${info.ticket}"
            else -> "○ Idle (${info.ticket})"
        }
    }

    private fun createActions() = DefaultActionGroup().apply {
        add(action("Previous Day", AllIcons.Actions.Back) { showDate(date.minusDays(1)) })
        add(action("Today", AllIcons.General.Locate, enabled = { date != LocalDate.now() }) { showDate(LocalDate.now()) })
        add(action("Next Day", AllIcons.Actions.Forward, enabled = { date.isBefore(LocalDate.now()) }) { showDate(date.plusDays(1)) })
        addSeparator()
        add(action("Add Time…", AllIcons.General.Add) { addEntry() })
        add(action("Remove Entry", AllIcons.General.Remove, enabled = { table.selectedRow >= 0 }) { removeSelected() })
        add(action("Refresh Summaries and Tempo Total", AllIcons.Actions.Refresh) { refreshRemote() })
        addSeparator()
        add(action("Submit Day to Tempo", AllIcons.Actions.Commit) { submit() })
        ActionManager.getInstance().getAction("TempoTracker.TogglePause")?.let { add(it) }
        add(action("Settings", AllIcons.General.Settings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, TempoConfigurable::class.java)
        })
    }

    private fun showDate(newDate: LocalDate) {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        date = newDate
        tempoTotal = null
        reload()
        refreshRemote()
    }

    private fun addEntry() {
        val ticket = Messages.showInputDialog(
            project, "Jira ticket:", "Add Time", null, BranchResolver.resolve(project).ticket.orEmpty(), null,
        )?.trim()?.uppercase()
        if (ticket.isNullOrEmpty()) return
        val text = Messages.showInputDialog(
            project, "Time to log (e.g. 1h 30m, 45m, 1.5h):", "Add Time", null, "30m", null,
        ) ?: return
        val seconds = Durations.parse(text)
        if (seconds == null || seconds <= 0) {
            Messages.showErrorDialog(project, "Couldn't read \"$text\" as a duration.", "Add Time")
            return
        }
        TimeStore.getInstance().addManual(date, ticket, seconds)
    }

    private fun removeSelected() {
        val entry = model.entryAt(table.selectedRow) ?: return
        val store = TimeStore.getInstance()
        if (entry.worklogId == 0L) {
            store.remove(entry.id)
            return
        }
        val answer = Messages.showYesNoDialog(
            project,
            "${entry.ticket} is already in Tempo. Remove that worklog on the next submit?",
            "Remove Entry",
            null,
        )
        if (answer == Messages.YES) store.update(entry.id) { it.excluded = true }
    }

    private fun refreshRemote() {
        val day = date
        ApplicationManager.getApplication().executeOnPooledThread {
            val total = runCatching { SubmitService.getInstance().refreshDay(day) }.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && day == date) {
                    tempoTotal = total
                    reload()
                }
            }
        }
    }

    private fun submit() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        val day = date
        val settings = TempoSettings.getInstance().state
        val pending = SubmitService.getInstance().pending(listOf(day))
        if (pending.isEmpty()) {
            SubmitService.notify(project, "Nothing to submit for $day.", NotificationType.INFORMATION)
            return
        }
        val lines = pending.joinToString("\n") {
            val action = LogPolicy.pendingAction(it, settings)
            "• ${it.ticket}: ${Durations.format(LogPolicy.secondsToLog(it, settings))} (${action.label})"
        }
        val ok = Messages.showOkCancelDialog(
            project, "Send to Tempo for $day:\n\n$lines", "Submit to Tempo", "Submit", "Cancel", null,
        )
        if (ok != Messages.OK) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Submitting to Tempo", true) {
            private var result: SubmitService.Result? = null

            override fun run(indicator: ProgressIndicator) {
                result = SubmitService.getInstance().submit(listOf(day), indicator)
            }

            override fun onSuccess() {
                val r = result ?: return
                val type = if (r.errors.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
                SubmitService.notify(project, r.summary(), type)
                refreshRemote()
            }

            override fun onThrowable(error: Throwable) {
                SubmitService.notify(
                    project,
                    "Submit failed: ${StringUtil.escapeXmlEntities(error.message.orEmpty())}",
                    NotificationType.ERROR,
                )
            }
        })
    }

    private fun action(
        text: String,
        icon: Icon?,
        enabled: () -> Boolean = { true },
        perform: () -> Unit,
    ): AnAction = object : DumbAwareAction(text, null, icon) {
        override fun actionPerformed(e: AnActionEvent) = perform()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    companion object {
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    }
}
