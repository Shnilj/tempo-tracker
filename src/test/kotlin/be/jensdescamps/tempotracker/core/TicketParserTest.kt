package be.jensdescamps.tempotracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TicketParserTest {
    @Test
    fun featureBranch() = assertEquals("ART01DEV10-4466", TicketParser.parse("feature/ART01DEV10-4466-this-is-a-test"))

    @Test
    fun bareKey() = assertEquals("ART01DEV10-4466", TicketParser.parse("ART01DEV10-4466"))

    @Test
    fun noTicket() {
        assertNull(TicketParser.parse("develop"))
        assertNull(TicketParser.parse("release/2026-10"))
        assertNull(TicketParser.parse(null))
    }

    @Test
    fun projectFilter() {
        assertNull(TicketParser.parse("feature/OPS-12-x", allowedProjectKeys = listOf("ART01DEV10")))
        assertEquals("ART01DEV10-1", TicketParser.parse("feature/OPS-12-ART01DEV10-1", allowedProjectKeys = listOf("art01dev10")))
    }

    @Test
    fun customPatternIsCaseInsensitive() =
        assertEquals("ABC-12", TicketParser.parse("feature/abc-12-thing", pattern = "(?i)([a-z][a-z0-9]+-\\d+)"))

    @Test
    fun invalidPattern() = assertNull(TicketParser.parse("feature/ABC-1", pattern = "(["))
}

class DurationsTest {
    @Test
    fun roundsUpToQuarterHour() {
        assertEquals(0L, Durations.roundUp(0, 15))
        assertEquals(900L, Durations.roundUp(1, 15))
        assertEquals(900L, Durations.roundUp(900, 15))
        assertEquals(1800L, Durations.roundUp(901, 15))
        assertEquals(901L, Durations.roundUp(901, 0))
    }

    @Test
    fun parses() {
        assertEquals(5400L, Durations.parse("1h 30m"))
        assertEquals(5400L, Durations.parse("1.5h"))
        assertEquals(5400L, Durations.parse("1,5h"))
        assertEquals(5400L, Durations.parse("90m"))
        assertEquals(5400L, Durations.parse("90"))
        assertEquals(5400L, Durations.parse("1:30"))
        assertNull(Durations.parse("soon"))
    }

    @Test
    fun formats() {
        assertEquals("1h 5m", Durations.format(3900))
        assertEquals("2h", Durations.format(7200))
        assertEquals("<1m", Durations.format(20))
    }
}
