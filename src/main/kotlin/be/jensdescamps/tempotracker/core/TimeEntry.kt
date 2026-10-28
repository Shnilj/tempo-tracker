package be.jensdescamps.tempotracker.core

import java.util.UUID

/**
 * Time on one ticket on one day. Serialized by the IntelliJ XML serializer, so every field
 * needs a default value (which also gives the class a no-arg constructor).
 */
data class TimeEntry(
    var id: String = UUID.randomUUID().toString(),
    /** ISO date, e.g. 2026-10-27. */
    var date: String = "",
    /** Jira key; empty = time on a branch without a ticket. */
    var ticket: String = "",
    /** Raw active IDE time. */
    var trackedSeconds: Long = 0,
    /** First moment of activity on this ticket that day, HH:mm:ss. */
    var startTime: String = "",
    /** Manually set duration to log; -1 = use rounded tracked time. */
    var overrideSeconds: Long = -1,
    /** Manually set description; empty = use the template from settings. */
    var description: String = "",
    var excluded: Boolean = false,
    var summary: String = "",
    var worklogId: Long = 0,
    var submittedSeconds: Long = 0,
    var submittedDescription: String = "",
    var submittedStartTime: String = "",
    var lastError: String = "",
)
