package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.settings.TempoSettings
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** One Tempo worklog: a start time and a duration. An entry is logged as one or more segments. */
data class Segment(val start: LocalTime, val seconds: Long) {
    val end: LocalTime get() = start.plusSeconds(seconds)
    val startText: String get() = start.format(CLOCK_FORMAT)

    override fun toString() = "$startText+$seconds"

    companion object {
        private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/** Decides what (if anything) an entry should look like in Tempo. */
object LogPolicy {
    enum class Action(val label: String) { NONE("up to date"), CREATE("new"), UPDATE("update"), DELETE("remove") }

    private const val DAY = 24 * 3600L
    private const val LAST_START = 23 * 3600 + 45 * 60L

    fun secondsToLog(e: TimeEntry, s: TempoSettings.State): Long = when {
        e.excluded || e.ticket.isBlank() -> 0
        e.overrideSeconds >= 0 -> e.overrideSeconds
        e.trackedSeconds < s.minimumMinutes * 60L -> 0
        else -> Durations.roundUp(e.trackedSeconds, s.roundingMinutes)
    }

    fun isFixed(e: TimeEntry): Boolean = e.fixedStart.isNotBlank()

    fun firstActivity(e: TimeEntry): LocalTime =
        runCatching { LocalTime.parse(e.startTime) }.getOrNull() ?: LocalTime.of(9, 0)

    /** An entry on its own: a ritual at its fixed time, anything else at its first activity rounded to the nearest step. */
    fun defaultSegments(e: TimeEntry, s: TempoSettings.State): List<Segment> {
        val start = runCatching { LocalTime.parse(e.fixedStart) }.getOrNull()
            ?: Durations.roundClock(firstActivity(e), s.roundingMinutes)
        return listOf(Segment(start, secondsToLog(e, s)))
    }

    /**
     * Worklog segments per entry id for all entries of one day.
     *
     * Rituals always sit at their fixed time. With back-to-back on, the other entries are chained in
     * order of first activity from the earliest first activity (rounded), flowing around the rituals:
     * an entry that would overlap one is split into a part before and a part after it.
     */
    fun plan(dayEntries: List<TimeEntry>, s: TempoSettings.State): Map<String, List<Segment>> {
        val result = LinkedHashMap<String, List<Segment>>()
        val busy = mutableListOf<Segment>()
        for (e in dayEntries.filter { isFixed(it) }) {
            val segments = defaultSegments(e, s)
            result[e.id] = segments
            busy += segments.filter { it.seconds > 0 }
        }
        busy.sortBy { it.start }

        if (s.backToBack) {
            val logged = dayEntries.filter { !isFixed(it) && secondsToLog(it, s) > 0 }.sortedBy { firstActivity(it) }
            val dayStart = logged.firstOrNull()?.let { firstActivity(it) } ?: LocalTime.of(9, 0)
            var cursor = Durations.roundClock(dayStart, s.roundingMinutes).toSecondOfDay().toLong()
            for (e in logged) {
                var remaining = secondsToLog(e, s)
                val segments = mutableListOf<Segment>()
                while (remaining > 0) {
                    cursor = skipBusy(cursor, busy)
                    val start = cursor.coerceAtMost(LAST_START)
                    val nextBusy = busy.map { it.start.toSecondOfDay().toLong() }.firstOrNull { it > start } ?: DAY
                    val chunk = if (start >= LAST_START) remaining else minOf(remaining, nextBusy - start)
                    segments += Segment(LocalTime.ofSecondOfDay(start), chunk)
                    cursor = start + chunk
                    remaining -= chunk
                }
                result[e.id] = segments
            }
        }
        dayEntries.filter { it.id !in result }.forEach { result[it.id] = defaultSegments(it, s) }
        return result
    }

    private fun skipBusy(cursor: Long, busy: List<Segment>): Long {
        var c = cursor
        var moved = true
        while (moved) {
            moved = false
            for (b in busy) {
                val from = b.start.toSecondOfDay().toLong()
                if (c >= from && c < from + b.seconds) {
                    c = from + b.seconds
                    moved = true
                }
            }
        }
        return c
    }

    /** Canonical form of what's (to be) in Tempo, to detect changes. */
    fun key(segments: List<Segment>): String = segments.filter { it.seconds > 0 }.joinToString(";")

    /** "08:30–09:30, 09:45–10:15" */
    fun timeRange(segments: List<Segment>?): String =
        segments.orEmpty().filter { it.seconds > 0 }
            .joinToString(", ") { "${it.start.toString().take(5)}–${it.end.toString().take(5)}" }

    private fun submittedKey(e: TimeEntry): String = e.submittedPlan.ifBlank {
        if (e.submittedStartTime.isNotBlank()) "${e.submittedStartTime}+${e.submittedSeconds}" else ""
    }

    fun description(e: TimeEntry, s: TempoSettings.State): String =
        e.description.ifBlank {
            s.descriptionTemplate
                .replace("{key}", e.ticket)
                .replace("{summary}", e.summary)
                .trim()
                .ifBlank { "Working on issue ${e.ticket}" }
        }

    /** @param segments planned segments from [plan]; when given, moved or split worklogs count as changed. */
    fun pendingAction(e: TimeEntry, s: TempoSettings.State, segments: List<Segment>? = null): Action {
        val seconds = secondsToLog(e, s)
        val submitted = submittedKey(e)
        // Worklogs sent before start times were remembered only move when back-to-back is on.
        val moved = segments != null &&
            (submitted.isNotBlank() || s.backToBack || isFixed(e)) &&
            submitted != key(segments)
        return when {
            seconds == 0L && e.worklogId != 0L -> Action.DELETE
            seconds == 0L -> Action.NONE
            e.worklogId == 0L -> Action.CREATE
            e.submittedSeconds != seconds || e.submittedDescription != description(e, s) || moved -> Action.UPDATE
            else -> Action.NONE
        }
    }

    fun status(e: TimeEntry, s: TempoSettings.State, segments: List<Segment>? = null): String {
        if (e.lastError.isNotBlank()) return "⚠ ${e.lastError}"
        return when (pendingAction(e, s, segments)) {
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
