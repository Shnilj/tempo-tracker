package be.jensdescamps.tempotracker.settings

import be.jensdescamps.tempotracker.core.Durations
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.EnumSet
import java.util.UUID

/**
 * A recurring meeting (standup, retro, planning…) logged at a fixed time on its own ticket.
 * Serialized by the IntelliJ XML serializer, so every field has a default.
 */
data class Ritual(
    var id: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var name: String = "",
    var ticket: String = "",
    /** e.g. "Mon-Fri", "Mon,Wed,Thu,Fri" or "ma,wo,do,vr". */
    var days: String = "Mon-Fri",
    /** H:mm */
    var start: String = "09:30",
    var minutes: Int = 15,
) {
    fun startTime(): LocalTime? = Durations.parseClock(start)

    fun appliesOn(date: LocalDate): Boolean =
        enabled && ticket.isNotBlank() && minutes > 0 && startTime() != null &&
            Rituals.parseDays(days)?.contains(date.dayOfWeek) == true

    /** Problem with this ritual's configuration, or null when it's fine. */
    fun validationError(): String? = when {
        !enabled -> null
        name.isBlank() -> "Every ritual needs a name"
        ticket.isBlank() -> "Ritual \"$name\" needs a Jira ticket"
        Rituals.parseDays(days) == null -> "Ritual \"$name\": can't read days \"$days\" (use e.g. Mon-Fri or Mon,Wed,Thu,Fri)"
        startTime() == null -> "Ritual \"$name\": start must look like 9:30"
        minutes <= 0 -> "Ritual \"$name\": duration must be at least 1 minute"
        else -> null
    }
}

object Rituals {
    private val NAMES: Map<String, DayOfWeek> = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY,
        // Dutch
        "ma" to DayOfWeek.MONDAY, "di" to DayOfWeek.TUESDAY, "wo" to DayOfWeek.WEDNESDAY,
        "do" to DayOfWeek.THURSDAY, "vr" to DayOfWeek.FRIDAY, "za" to DayOfWeek.SATURDAY,
        "zo" to DayOfWeek.SUNDAY,
    )

    /** Parses "Mon-Fri", "Mon,Wed,Thu,Fri", "ma wo do vr", "Fri-Mon"… Returns null if unreadable. */
    fun parseDays(text: String): Set<DayOfWeek>? {
        val tokens = text.lowercase().split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val result = EnumSet.noneOf(DayOfWeek::class.java)
        for (token in tokens) {
            val parts = token.split('-').map { it.trim() }
            when (parts.size) {
                1 -> result.add(day(parts[0]) ?: return null)
                2 -> {
                    val from = day(parts[0]) ?: return null
                    val to = day(parts[1]) ?: return null
                    var d = from
                    while (true) {
                        result.add(d)
                        if (d == to) break
                        d = d.plus(1)
                    }
                }
                else -> return null
            }
        }
        return result
    }

    /** The ritual running at [time] on [date], if any. */
    fun activeAt(date: LocalDate, time: LocalTime, rituals: List<Ritual>): Ritual? =
        rituals.firstOrNull { r ->
            val start = r.startTime() ?: return@firstOrNull false
            r.appliesOn(date) && !time.isBefore(start) && time.isBefore(start.plusMinutes(r.minutes.toLong()))
        }

    private fun day(token: String): DayOfWeek? = NAMES[token.take(3)] ?: NAMES[token.take(2)]
}
