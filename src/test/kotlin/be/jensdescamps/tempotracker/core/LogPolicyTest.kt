package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.settings.TempoSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class LogPolicyTest {
    private val a = TimeEntry(id = "a", ticket = "ABC-1", startTime = "08:33:10", trackedSeconds = 50 * 60) // → 1h
    private val b = TimeEntry(id = "b", ticket = "ABC-2", startTime = "08:50:00", trackedSeconds = 20 * 60) // → 30m
    private val c = TimeEntry(id = "c", ticket = "ABC-3", startTime = "13:07:00", trackedSeconds = 10 * 60) // → 15m

    @Test
    fun eachAtOwnStartByDefault() {
        val plan = LogPolicy.plan(listOf(a, b, c), TempoSettings.State())
        assertEquals(mapOf("a" to "08:30:00", "b" to "08:45:00", "c" to "13:00:00"), plan)
    }

    @Test
    fun backToBackChainsFromFirstActivity() {
        val s = TempoSettings.State().apply { backToBack = true }
        val plan = LogPolicy.plan(listOf(c, b, a), s)
        assertEquals(mapOf("a" to "08:30:00", "b" to "09:30:00", "c" to "10:00:00"), plan)
    }
}
