package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.settings.Layout
import be.jensdescamps.tempotracker.settings.TempoSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {
    private val timeline = TempoSettings.State().apply { layout = Layout.TIMELINE.name }

    private fun entry(id: String, ticket: String, sessions: String) = TimeEntry(
        id = id,
        ticket = ticket,
        sessions = sessions,
        startTime = sessions.substringBefore('-'),
        trackedSeconds = Sessions.parse(sessions).sumOf { it.second - it.first },
    )

    private fun ranges(vararg entries: TimeEntry) =
        LogPolicy.plan(entries.toList(), timeline).mapValues { LogPolicy.timeRange(it.value) }

    @Test
    fun interruptedTicketBecomesTwoWorklogs() = assertEquals(
        mapOf("abc" to "10:00–11:00, 13:00–15:00", "sup" to "11:00–12:00"),
        ranges(
            entry("abc", "ABC-01", "10:00:00-11:03:00;13:00:00-15:00:00"),
            entry("sup", "SUP-1", "11:03:00-12:00:00"),
        ),
    )

    @Test
    fun shortDetourIsAbsorbed() = assertEquals(
        mapOf("abc" to "10:00–11:30", "sup" to ""),
        ranges(
            entry("abc", "ABC-01", "10:00:00-10:40:00;10:45:00-11:30:00"),
            entry("sup", "SUP-1", "10:40:00-10:45:00"),
        ),
    )

    @Test
    fun shortIdleGapIsFilled() = assertEquals(
        mapOf("abc" to "10:00–11:00"),
        ranges(entry("abc", "ABC-01", "10:00:00-10:20:00;10:32:00-11:00:00")),
    )

    @Test
    fun lunchSplitsTheBlock() = assertEquals(
        mapOf("abc" to "10:00–12:00, 13:00–14:00"),
        ranges(entry("abc", "ABC-01", "10:00:00-12:00:00;13:00:00-14:00:00")),
    )

    @Test
    fun ritualSlotStaysFree() {
        val standup = TimeEntry(
            id = "s", ticket = "MEET-1", ritualId = "r", fixedStart = "09:30:00", startTime = "09:30:00", overrideSeconds = 900,
        )
        assertEquals(
            mapOf("s" to "09:30–09:45", "abc" to "09:00–09:30, 09:45–10:30"),
            ranges(standup, entry("abc", "ABC-01", "09:00:00-09:31:00;09:44:00-10:30:00")),
        )
    }

    @Test
    fun loneShortTaskGetsItsBestFreeSlot() = assertEquals(
        mapOf("abc" to "10:00–11:00", "sup" to "14:00–14:15"),
        ranges(
            entry("abc", "ABC-01", "10:00:00-11:00:00"),
            entry("sup", "SUP-1", "14:02:00-14:08:00"),
        ),
    )

    @Test
    fun loggedSecondsFollowTheBlocks() {
        val abc = entry("abc", "ABC-01", "10:00:00-11:03:00;13:00:00-15:00:00")
        val plan = LogPolicy.plan(listOf(abc), timeline)
        assertEquals(3 * 3600L, LogPolicy.plannedSeconds(abc, timeline, plan["abc"]))
    }

    @Test
    fun sessionsExtendAndSplit() {
        var s = Sessions.extend("", "10:00:00", "10:00:30")
        s = Sessions.extend(s, "10:00:30", "10:01:00")
        s = Sessions.extend(s, "10:02:30", "10:03:00") // 90 s gap: still the same session
        s = Sessions.extend(s, "10:30:00", "10:30:30") // new session
        assertEquals("10:00:00-10:03:00;10:30:00-10:30:30", s)
    }
}
