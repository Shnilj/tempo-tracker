package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.settings.Ritual
import be.jensdescamps.tempotracker.settings.Rituals
import be.jensdescamps.tempotracker.settings.TempoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime

class LogPolicyTest {
    private val a = TimeEntry(id = "a", ticket = "ABC-1", startTime = "08:33:10", trackedSeconds = 50 * 60) // → 1h
    private val b = TimeEntry(id = "b", ticket = "ABC-2", startTime = "08:50:00", trackedSeconds = 20 * 60) // → 30m
    private val c = TimeEntry(id = "c", ticket = "ABC-3", startTime = "13:07:00", trackedSeconds = 10 * 60) // → 15m
    private val standup = TimeEntry(
        id = "s", ticket = "MEET-1", ritualId = "r1", fixedStart = "09:30:00", startTime = "09:30:00", overrideSeconds = 15 * 60,
    )

    private fun ranges(entries: List<TimeEntry>, s: TempoSettings.State) =
        LogPolicy.plan(entries, s).mapValues { LogPolicy.timeRange(it.value) }

    @Test
    fun eachAtOwnStartByDefault() = assertEquals(
        mapOf("a" to "08:30–09:30", "b" to "08:45–09:15", "c" to "13:00–13:15", "s" to "09:30–09:45"),
        ranges(listOf(a, b, c, standup), TempoSettings.State()),
    )

    @Test
    fun backToBackChainsFromFirstActivity() = assertEquals(
        mapOf("a" to "08:30–09:30", "b" to "09:30–10:00", "c" to "10:00–10:15"),
        ranges(listOf(c, b, a), TempoSettings.State().apply { backToBack = true }),
    )

    @Test
    fun backToBackFlowsAroundRituals() = assertEquals(
        // a fills 08:30–09:30 exactly, standup 09:30–09:45, b continues after it, c after b
        mapOf("s" to "09:30–09:45", "a" to "08:30–09:30", "b" to "09:45–10:15", "c" to "10:15–10:30"),
        ranges(listOf(a, b, c, standup), TempoSettings.State().apply { backToBack = true }),
    )

    @Test
    fun backToBackSplitsAnEntryOverlappingARitual() {
        val long = a.copy(trackedSeconds = 80 * 60) // → 1h 30m from 08:30, standup at 09:30
        assertEquals(
            mapOf("s" to "09:30–09:45", "a" to "08:30–09:30, 09:45–10:15"),
            ranges(listOf(long, standup), TempoSettings.State().apply { backToBack = true }),
        )
    }

    @Test
    fun splitWorklogNeedsUpdateWhenPlanChanges() {
        val s = TempoSettings.State().apply { backToBack = true }
        val logged = a.copy(worklogId = 1, submittedSeconds = 3600, submittedDescription = "Working on issue ABC-1", submittedPlan = "08:30:00+3600")
        val planned = LogPolicy.plan(listOf(logged, standup), s)
        assertEquals(LogPolicy.Action.NONE, LogPolicy.pendingAction(logged, s, planned["a"]))
        val longer = logged.copy(trackedSeconds = 80 * 60)
        assertEquals(LogPolicy.Action.UPDATE, LogPolicy.pendingAction(longer, s, LogPolicy.plan(listOf(longer, standup), s)["a"]))
    }
}

class RitualsTest {
    @Test
    fun parsesDays() {
        assertEquals(setOf(MONDAY, WEDNESDAY, THURSDAY, FRIDAY), Rituals.parseDays("Mon,Wed,Thu,Fri"))
        assertEquals(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), Rituals.parseDays("Mon-Fri"))
        assertEquals(setOf(MONDAY, WEDNESDAY, THURSDAY, FRIDAY), Rituals.parseDays("ma wo do vr"))
        assertEquals(setOf(TUESDAY), Rituals.parseDays("tuesday"))
        assertNull(Rituals.parseDays("someday"))
        assertNull(Rituals.parseDays(""))
    }

    @Test
    fun activeAt() {
        val standup = Ritual(id = "s", name = "Standup", ticket = "MEET-1", days = "Mon,Wed-Fri", start = "9:30", minutes = 15)
        val retro = Ritual(id = "r", name = "Retro & planning", ticket = "MEET-2", days = "Tue", start = "10:00", minutes = 90)
        val rituals = listOf(standup, retro)
        val tuesday = LocalDate.of(2026, 10, 27)
        val wednesday = tuesday.plusDays(1)
        assertEquals("s", Rituals.activeAt(wednesday, LocalTime.of(9, 40), rituals)?.id)
        assertNull(Rituals.activeAt(wednesday, LocalTime.of(9, 45), rituals))
        assertNull(Rituals.activeAt(tuesday, LocalTime.of(9, 40), rituals))
        assertEquals("r", Rituals.activeAt(tuesday, LocalTime.of(11, 29), rituals)?.id)
    }
}
