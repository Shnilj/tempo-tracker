package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.settings.TempoSettings

/** Decides what (if anything) an entry should look like in Tempo. */
object LogPolicy {
    enum class Action(val label: String) { NONE("up to date"), CREATE("new"), UPDATE("update"), DELETE("remove") }

    fun secondsToLog(e: TimeEntry, s: TempoSettings.State): Long = when {
        e.excluded || e.ticket.isBlank() -> 0
        e.overrideSeconds >= 0 -> e.overrideSeconds
        e.trackedSeconds < s.minimumMinutes * 60L -> 0
        else -> Durations.roundUp(e.trackedSeconds, s.roundingMinutes)
    }

    fun description(e: TimeEntry, s: TempoSettings.State): String =
        e.description.ifBlank {
            s.descriptionTemplate
                .replace("{key}", e.ticket)
                .replace("{summary}", e.summary)
                .trim()
                .ifBlank { "Working on issue ${e.ticket}" }
        }

    fun pendingAction(e: TimeEntry, s: TempoSettings.State): Action {
        val seconds = secondsToLog(e, s)
        return when {
            seconds == 0L && e.worklogId != 0L -> Action.DELETE
            seconds == 0L -> Action.NONE
            e.worklogId == 0L -> Action.CREATE
            e.submittedSeconds != seconds || e.submittedDescription != description(e, s) -> Action.UPDATE
            else -> Action.NONE
        }
    }

    fun status(e: TimeEntry, s: TempoSettings.State): String {
        if (e.lastError.isNotBlank()) return "⚠ ${e.lastError}"
        return when (pendingAction(e, s)) {
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
