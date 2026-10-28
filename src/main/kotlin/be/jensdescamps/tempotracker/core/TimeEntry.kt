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
    /** Canonical "HH:mm:ss+seconds;…" of what was last sent to Tempo (see LogPolicy.key). */
    var submittedPlan: String = "",
    /** Worklog ids of the 2nd, 3rd… segment when an entry is split around rituals, comma-separated. */
    var extraWorklogIds: String = "",
    /** Set for entries created from a ritual. */
    var ritualId: String = "",
    /** Fixed worklog start (HH:mm:ss) for rituals; empty = planned from activity. */
    var fixedStart: String = "",
    /** Actual work sessions, "HH:mm:ss-HH:mm:ss;…" (used by the real-timeline layout). */
    var sessions: String = "",
    var lastError: String = "",
)
