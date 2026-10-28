package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.settings.Layout
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

    /** Idle gaps up to this long between two blocks of the same ticket are logged as part of it. */
    private const val GAP_FILL_SECONDS = 15 * 60L

    fun layoutOf(s: TempoSettings.State): Layout =
        runCatching { Layout.valueOf(s.layout) }.getOrNull() ?: if (s.backToBack) Layout.BACK_TO_BACK else Layout.OWN_START

    fun secondsToLog(e: TimeEntry, s: TempoSettings.State): Long = when {
        e.excluded || e.ticket.isBlank() -> 0
        e.overrideSeconds >= 0 -> e.overrideSeconds
        e.trackedSeconds < s.minimumMinutes * 60L -> 0
        else -> Durations.roundUp(e.trackedSeconds, s.roundingMinutes)
    }

    fun isFixed(e: TimeEntry): Boolean = e.fixedStart.isNotBlank()

    /** Placed from its recorded sessions (real-timeline layout) rather than from its total. */
    fun usesTimeline(e: TimeEntry, s: TempoSettings.State): Boolean =
        layoutOf(s) == Layout.TIMELINE && !isFixed(e) && e.sessions.isNotBlank() && e.overrideSeconds < 0

    /** What will actually be logged: the sum of the timeline blocks, or the rounded total. */
    fun plannedSeconds(e: TimeEntry, s: TempoSettings.State, segments: List<Segment>?): Long =
        if (segments != null && usesTimeline(e, s)) segments.sumOf { it.seconds } else secondsToLog(e, s)

    fun firstActivity(e: TimeEntry): LocalTime =
        runCatching { LocalTime.parse(e.startTime) }.getOrNull() ?: LocalTime.of(9, 0)

    /** An entry on its own: a ritual at its fixed time, anything else at its first activity rounded to the nearest step. */
    fun defaultSegments(e: TimeEntry, s: TempoSettings.State): List<Segment> {
        val start = runCatching { LocalTime.parse(e.fixedStart) }.getOrNull()
            ?: Durations.roundClock(firstActivity(e), s.roundingMinutes)
        return listOf(Segment(start, secondsToLog(e, s)))
    }

    /**
     * Worklog segments per entry id for all entries of one day. Rituals always sit at their fixed time.
     * - OWN_START: each entry is one worklog at its first activity (rounded).
     * - BACK_TO_BACK: entries are chained in order of first activity, flowing around rituals.
     * - TIMELINE: each quarter hour goes to the ticket you spent most of it on; neighbouring quarters of
     *   the same ticket form one worklog. Entries without sessions (manual time) fill free slots.
     */
    fun plan(dayEntries: List<TimeEntry>, s: TempoSettings.State): Map<String, List<Segment>> {
        val result = LinkedHashMap<String, List<Segment>>()
        val busy = mutableListOf<Segment>()
        for (e in dayEntries.filter { isFixed(it) }) {
            val segments = defaultSegments(e, s)
            result[e.id] = segments
            busy += segments.filter { it.seconds > 0 }
        }
        val flexible = dayEntries.filter { !isFixed(it) }

        when (layoutOf(s)) {
            Layout.OWN_START -> {}
            Layout.BACK_TO_BACK -> {
                val logged = flexible.filter { secondsToLog(it, s) > 0 }.sortedBy { firstActivity(it) }
                val dayStart = logged.firstOrNull()?.let { firstActivity(it) } ?: LocalTime.of(9, 0)
                var cursor = Durations.roundClock(dayStart, s.roundingMinutes).toSecondOfDay().toLong()
                for (e in logged) {
                    val segments = place(cursor, secondsToLog(e, s), busy)
                    result[e.id] = segments
                    segments.lastOrNull()?.let { cursor = it.start.toSecondOfDay() + it.seconds }
                }
            }
            Layout.TIMELINE -> {
                val participants = flexible.filter { it.sessions.isNotBlank() && it.overrideSeconds < 0 }
                val owned = timeline(participants, s, busy)
                for (e in participants) {
                    val segments = owned[e.id].orEmpty()
                    busy += segments // slots won by unticked / no-ticket rows stay empty in Tempo
                    result[e.id] = if (e.excluded || e.ticket.isBlank()) emptyList() else segments
                }
                // Manual time and edited durations: into the first free slots from their own start.
                flexible.filter { it.id !in result && secondsToLog(it, s) > 0 }.sortedBy { firstActivity(it) }.forEach { e ->
                    val from = Durations.roundClock(firstActivity(e), s.roundingMinutes).toSecondOfDay().toLong()
                    val segments = place(from, secondsToLog(e, s), busy)
                    result[e.id] = segments
                    busy += segments
                }
            }
        }
        dayEntries.filter { it.id !in result }.forEach { result[it.id] = defaultSegments(it, s) }
        return result
    }

    /** Puts [seconds] from [from] onwards into time not taken by [busy], splitting around it. */
    private fun place(from: Long, seconds: Long, busy: List<Segment>): List<Segment> {
        val segments = mutableListOf<Segment>()
        var cursor = from
        var remaining = seconds
        while (remaining > 0) {
            cursor = skipBusy(cursor, busy)
            val start = cursor.coerceAtMost(LAST_START)
            val nextBusy = busy.map { it.start.toSecondOfDay().toLong() }.filter { it > start }.minOrNull() ?: DAY
            val chunk = if (start >= LAST_START) remaining else minOf(remaining, nextBusy - start)
            segments += Segment(LocalTime.ofSecondOfDay(start), chunk)
            cursor = start + chunk
            remaining -= chunk
        }
        return segments
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

    /** Quarter-hour (rounding step) slots → owning entry, turned into blocks per entry. */
    private fun timeline(entries: List<TimeEntry>, s: TempoSettings.State, busy: List<Segment>): Map<String, List<Segment>> {
        val step = (if (s.roundingMinutes > 0) s.roundingMinutes else 15) * 60L
        val slotCount = (DAY / step).toInt()
        val owner = arrayOfNulls<String>(slotCount)
        val blocked = BooleanArray(slotCount) { i ->
            val from = i * step
            busy.any { b -> val bFrom = b.start.toSecondOfDay().toLong(); bFrom < from + step && from < bFrom + b.seconds }
        }

        // Seconds worked per slot per entry.
        val perSlot = HashMap<Int, HashMap<String, Long>>()
        for (e in entries) {
            for ((from, to) in Sessions.parse(e.sessions)) {
                var t = from
                while (t < to) {
                    val slot = (t / step).toInt().coerceAtMost(slotCount - 1)
                    val chunk = minOf(to, (slot + 1) * step) - t
                    if (chunk <= 0) break
                    perSlot.getOrPut(slot) { HashMap() }.merge(e.id, chunk) { a, b -> a + b }
                    t += chunk
                }
            }
        }

        // 1. A slot you worked at least half of goes to the ticket with the most time in it.
        for ((slot, seconds) in perSlot) {
            if (blocked[slot] || seconds.values.sum() * 2 < step) continue
            owner[slot] = seconds.maxByOrNull { it.value }?.key
        }

        // 2. Short idle gaps between two blocks of the same ticket belong to that ticket.
        val maxGapSlots = (GAP_FILL_SECONDS / step).toInt()
        var i = 0
        while (i < slotCount) {
            if (owner[i] != null || blocked[i]) { i++; continue }
            var j = i
            while (j < slotCount && owner[j] == null && !blocked[j]) j++
            val before = if (i > 0) owner[i - 1] else null
            val after = if (j < slotCount) owner[j] else null
            if (before != null && before == after && j - i <= maxGapSlots) for (k in i until j) owner[k] = before
            i = j
        }

        // 3. A ticket you did spend time on but that lost every slot gets its best free slot, if any.
        val minimum = s.minimumMinutes * 60L
        for (e in entries) {
            if (owner.any { it == e.id } || e.trackedSeconds < minimum) continue
            val best = perSlot.entries
                .filter { (slot, _) -> owner[slot] == null && !blocked[slot] }
                .maxByOrNull { it.value[e.id] ?: 0L }
                ?.takeIf { (it.value[e.id] ?: 0L) > 0 }
                ?.key
            if (best != null) owner[best] = e.id
        }

        // Contiguous slots of one entry → one segment.
        val result = HashMap<String, MutableList<Segment>>()
        var k = 0
        while (k < slotCount) {
            val id = owner[k]
            if (id == null) { k++; continue }
            var end = k
            while (end + 1 < slotCount && owner[end + 1] == id) end++
            result.getOrPut(id) { mutableListOf() } += Segment(LocalTime.ofSecondOfDay(k * step), (end - k + 1) * step)
            k = end + 1
        }
        return result
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
        val seconds = plannedSeconds(e, s, segments)
        val submitted = submittedKey(e)
        // Worklogs sent before start times were remembered only move with a layout that places them.
        val moved = segments != null &&
            (submitted.isNotBlank() || layoutOf(s) != Layout.OWN_START || isFixed(e)) &&
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
                segments != null && usesTimeline(e, s) && e.trackedSeconds > 0 -> "Too short, absorbed"
                else -> "Below minimum"
            }
            Action.CREATE -> "Not logged"
            Action.UPDATE -> "Changed"
            Action.DELETE -> "Will be removed"
        }
    }
}
