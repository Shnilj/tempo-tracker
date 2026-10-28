package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.settings.TempoSettings
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Decides what (if anything) an entry should look like in Tempo. */
object LogPolicy {
    enum class Action(val label: String) { NONE("up to date"), CREATE("new"), UPDATE("update"), DELETE("remove") }

    fun secondsToLog(e: TimeEntry, s: TempoSettings.State): Long = when {
        e.excluded || e.ticket.isBlank() -> 0
        e.overrideSeconds >= 0 -> e.overrideSeconds
        e.trackedSeconds < s.minimumMinutes * 60L -> 0
        else -> Durations.roundUp(e.trackedSeconds, s.roundingMinutes)
    }

    private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private const val LAST_START = 23 * 3600 + 45 * 60L

    fun firstActivity(e: TimeEntry): LocalTime =
        runCatching { LocalTime.parse(e.startTime) }.getOrNull() ?: LocalTime.of(9, 0)

    /** Worklog start on its own: first activity rounded to the nearest step. */
    fun startTime(e: TimeEntry, s: TempoSettings.State): String =
        Durations.roundClock(firstActivity(e), s.roundingMinutes).format(CLOCK_FORMAT)

    /**
     * Start time (HH:mm:ss) per entry id for all entries of one day. With back-to-back enabled the
     * entries that will be logged are chained in order of first activity, starting at the earliest
     * first activity (rounded); each next one starts where the previous one ends.
     */
    fun plan(dayEntries: List<TimeEntry>, s: TempoSettings.State): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (s.backToBack) {
            val logged = dayEntries.filter { secondsToLog(it, s) > 0 }.sortedBy { firstActivity(it) }
            val dayStart = logged.firstOrNull()?.let { firstActivity(it) } ?: LocalTime.of(9, 0)
            var cursor = Durations.roundClock(dayStart, s.roundingMinutes).toSecondOfDay().toLong()
            for (e in logged) {
                val start = cursor.coerceAtMost(LAST_START)
                result[e.id] = LocalTime.ofSecondOfDay(start).format(CLOCK_FORMAT)
                cursor = start + secondsToLog(e, s)
            }
        }
        dayEntries.filter { it.id !in result }.forEach { result[it.id] = startTime(it, s) }
        return result
    }

    fun description(e: TimeEntry, s: TempoSettings.State): String =
        e.description.ifBlank {
            s.descriptionTemplate
                .replace("{key}", e.ticket)
                .replace("{summary}", e.summary)
                .trim()
                .ifBlank { "Working on issue ${e.ticket}" }
        }

    /** @param start planned start from [plan]; when given, a moved worklog counts as changed. */
    fun pendingAction(e: TimeEntry, s: TempoSettings.State, start: String? = null): Action {
        val seconds = secondsToLog(e, s)
        // Worklogs sent before start times were remembered only move when back-to-back is on.
        val moved = start != null && (e.submittedStartTime.isNotBlank() || s.backToBack) && e.submittedStartTime != start
        return when {
            seconds == 0L && e.worklogId != 0L -> Action.DELETE
            seconds == 0L -> Action.NONE
            e.worklogId == 0L -> Action.CREATE
            e.submittedSeconds != seconds || e.submittedDescription != description(e, s) || moved -> Action.UPDATE
            else -> Action.NONE
        }
    }

    fun status(e: TimeEntry, s: TempoSettings.State, start: String? = null): String {
        if (e.lastError.isNotBlank()) return "⚠ ${e.lastError}"
        return when (pendingAction(e, s, start)) {
            Action.NONE -> when {
                e.worklogId != 0L -> "✓ Logged"
                e.ticket.isBlank() -> "No ticket"
                e.excluded -> "Skipped"
                else -> "Below minimum"
            }
            Action.CREATE -> "Not logged"
            Action.UPDATE -> "Changed"
            Action.DELETE -> "Will be removed"
        }
    }
}
