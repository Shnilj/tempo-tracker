package be.jensdescamps.tempotracker.core

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

object Durations {
    private val CLOCK = DateTimeFormatter.ofPattern("H:mm")
    private val HOURS_MINUTES = Regex("""^(?:(\d+(?:\.\d+)?)\s*h)?\s*(?:(\d+)\s*m(?:in)?)?$""")
    private val COLON = Regex("""^(\d+):(\d{1,2})$""")

    /** Rounds up to the next multiple of [stepMinutes]; 0 stays 0, stepMinutes <= 0 disables rounding. */
    fun roundUp(seconds: Long, stepMinutes: Int): Long {
        if (seconds <= 0) return 0
        if (stepMinutes <= 0) return seconds
        val step = stepMinutes * 60L
        return ((seconds + step - 1) / step) * step
    }

    fun format(seconds: Long): String {
        if (seconds in 1..59) return "<1m"
        val totalMinutes = seconds / 60
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    /** Parses "1h 30m", "1.5h", "90m", "90" (minutes) or "1:30". Returns seconds, or null if unreadable. */
    fun parse(text: String): Long? {
        val t = text.trim().lowercase().replace(',', '.')
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { return it * 60 }
        COLON.matchEntire(t)?.let { return it.groupValues[1].toLong() * 3600 + it.groupValues[2].toLong() * 60 }
        val m = HOURS_MINUTES.matchEntire(t) ?: return null
        val h = m.groupValues[1]
        val min = m.groupValues[2]
        if (h.isEmpty() && min.isEmpty()) return null
        val hours = if (h.isEmpty()) 0.0 else h.toDouble()
        val minutes = if (min.isEmpty()) 0L else min.toLong()
        return (hours * 3600).roundToLong() + minutes * 60
    }

    /** Parses a clock time such as "17:30" or "9:00". */
    fun parseClock(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim(), CLOCK) }.getOrNull()
}
