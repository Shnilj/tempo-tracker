package be.jensdescamps.tempotracker.core

import be.jensdescamps.tempotracker.settings.Ritual
import be.jensdescamps.tempotracker.settings.TempoSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import java.time.LocalDate

/** Fired (on any thread) whenever tracked time, entries or tracker state change. */
fun interface TimeStoreListener {
    fun changed()

    companion object {
        @JvmField
        val TOPIC: Topic<TimeStoreListener> = Topic.create("Tempo Tracker changes", TimeStoreListener::class.java)

        fun publish() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).changed()
        }
    }
}

/** Tracked time per ticket per day, stored locally (not synced between machines). */
@Service(Service.Level.APP)
@State(
    name = "TempoTrackerTime",
    storages = [Storage("tempo-tracker-time.xml", roamingType = RoamingType.DISABLED)],
)
class TimeStore : PersistentStateComponent<TimeStore.State> {

    class State {
        var entries: MutableList<TimeEntry> = mutableListOf()
        /** Manual branch → ticket assignments for branches without a key in their name. */
        var branchTickets: MutableMap<String, String> = mutableMapOf()
        var lastDailyRun: String = ""
    }

    private val lock = Any()
    private var data = State()

    override fun getState(): State = synchronized(lock) {
        State().also { copy ->
            copy.entries = data.entries.map { it.copy() }.toMutableList()
            copy.branchTickets = LinkedHashMap(data.branchTickets)
            copy.lastDailyRun = data.lastDailyRun
        }
    }

    override fun loadState(state: State) {
        synchronized(lock) {
            data = state
            val cutoff = LocalDate.now().minusDays(90).toString()
            data.entries.removeIf { it.date < cutoff }
        }
    }

    // ---- reads (always copies) ----

    fun entries(date: LocalDate): List<TimeEntry> = synchronized(lock) {
        val d = date.toString()
        data.entries.filter { it.date == d }.map { it.copy() }
    }

    fun assignedTicket(branch: String): String? = synchronized(lock) { data.branchTickets[branch] }

    var lastDailyRun: String
        get() = synchronized(lock) { data.lastDailyRun }
        set(value) = synchronized(lock) { data.lastDailyRun = value }

    // ---- writes ----

    /** Adds [seconds] of work that ended at [endTime] (HH:mm:ss) and started at [startTime]. */
    fun credit(date: LocalDate, ticket: String, seconds: Long, startTime: String, endTime: String) = write {
        val d = date.toString()
        val entry = data.entries.firstOrNull { it.date == d && it.ticket == ticket && it.ritualId.isEmpty() }
            ?: TimeEntry(date = d, ticket = ticket, startTime = startTime).also { data.entries.add(it) }
        entry.trackedSeconds += seconds
        entry.sessions = Sessions.extend(entry.sessions, startTime, endTime)
    }

    /** Adds manually entered time; merges into an existing entry for the same ticket and day. */
    fun addManual(date: LocalDate, ticket: String, seconds: Long) = write {
        val settings = TempoSettings.getInstance().state
        val d = date.toString()
        val existing = data.entries.firstOrNull { it.date == d && it.ticket == ticket && it.ritualId.isEmpty() }
        if (existing != null) {
            existing.overrideSeconds = LogPolicy.secondsToLog(existing.copy(excluded = false), settings) + seconds
            existing.excluded = false
        } else {
            data.entries.add(TimeEntry(date = d, ticket = ticket, startTime = "09:00:00", overrideSeconds = seconds))
        }
    }

    fun update(id: String, block: (TimeEntry) -> Unit) = write {
        data.entries.firstOrNull { it.id == id }?.let(block)
    }

    fun remove(id: String) = write { data.entries.removeIf { it.id == id } }

    /** Moves an entry that isn't in Tempo yet to another ticket, merging with that ticket's entry if any. */
    fun reassign(id: String, newTicket: String) = write {
        val settings = TempoSettings.getInstance().state
        val entry = data.entries.firstOrNull { it.id == id } ?: return@write
        if (entry.worklogId != 0L || entry.ticket == newTicket) return@write
        if (entry.ritualId.isNotEmpty()) { // rituals keep their own row
            entry.ticket = newTicket
            entry.summary = ""
            entry.lastError = ""
            return@write
        }
        val target = data.entries.firstOrNull {
            it !== entry && it.date == entry.date && it.ticket == newTicket && it.ritualId.isEmpty()
        }
        if (target == null) {
            entry.ticket = newTicket
            entry.summary = ""
            entry.lastError = ""
            return@write
        }
        if (entry.overrideSeconds >= 0 || target.overrideSeconds >= 0) {
            val moved = entry.copy(ticket = newTicket, excluded = false)
            target.overrideSeconds = LogPolicy.secondsToLog(target.copy(excluded = false), settings) +
                LogPolicy.secondsToLog(moved, settings)
        }
        target.trackedSeconds += entry.trackedSeconds
        if (entry.startTime.isNotBlank() && (target.startTime.isBlank() || entry.startTime < target.startTime)) {
            target.startTime = entry.startTime
        }
        data.entries.remove(entry)
    }

    /** True once you've worked on [date] (rituals alone don't count). */
    fun hasActivity(date: LocalDate): Boolean = synchronized(lock) {
        val d = date.toString()
        data.entries.any { it.date == d && it.ritualId.isEmpty() && it.trackedSeconds > 0 }
    }

    /** True if the ritual's row for [date] was unticked (meeting cancelled). */
    fun isRitualSkipped(date: LocalDate, ritualId: String): Boolean = synchronized(lock) {
        val d = date.toString()
        data.entries.any { it.date == d && it.ritualId == ritualId && it.excluded }
    }

    /**
     * Adds a fixed-time row for every ritual on [date] that doesn't have one yet. Rows are never
     * deleted (only unticked), so a cancelled meeting doesn't come back.
     */
    fun ensureRituals(date: LocalDate, rituals: List<Ritual>) {
        val d = date.toString()
        val due = rituals.filter { it.appliesOn(date) }
        val missing = synchronized(lock) { due.filter { r -> data.entries.none { it.date == d && it.ritualId == r.id } } }
        if (missing.isEmpty()) return
        write {
            for (r in missing) {
                if (data.entries.any { it.date == d && it.ritualId == r.id }) continue
                val start = r.startTime()?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) ?: continue
                data.entries.add(
                    TimeEntry(
                        date = d,
                        ticket = r.ticket.trim().uppercase(),
                        ritualId = r.id,
                        fixedStart = start,
                        startTime = start,
                        overrideSeconds = r.minutes * 60L,
                        description = r.name.trim(),
                    ),
                )
            }
        }
    }

    fun assignTicket(branch: String, ticket: String?) = write {
        if (ticket.isNullOrBlank()) data.branchTickets.remove(branch) else data.branchTickets[branch] = ticket
    }

    private fun write(block: () -> Unit) {
        synchronized(lock) { block() }
        TimeStoreListener.publish()
    }

    companion object {
        fun getInstance(): TimeStore = service()
    }
}

/** Work sessions stored as "HH:mm:ss-HH:mm:ss;…". */
object Sessions {
    /** Gaps up to this long (a missed tick, a short detour) continue the previous session. */
    private const val CONTINUE_SECONDS = 120

    fun parse(text: String): List<Pair<Long, Long>> = text.split(';').mapNotNull { part ->
        val bounds = part.split('-')
        if (bounds.size != 2) return@mapNotNull null
        val from = runCatching { java.time.LocalTime.parse(bounds[0].trim()).toSecondOfDay().toLong() }.getOrNull()
        val to = runCatching { java.time.LocalTime.parse(bounds[1].trim()).toSecondOfDay().toLong() }.getOrNull()
        if (from == null || to == null) return@mapNotNull null
        from to (if (to < from) 24 * 3600L else to) // crossed midnight: end of day
    }

    fun extend(text: String, startTime: String, endTime: String): String {
        val sessions = parse(text).toMutableList()
        val from = runCatching { java.time.LocalTime.parse(startTime).toSecondOfDay().toLong() }.getOrNull() ?: return text
        val to = runCatching { java.time.LocalTime.parse(endTime).toSecondOfDay().toLong() }.getOrNull() ?: return text
        if (to <= from) return text
        val last = sessions.lastOrNull()
        if (last != null && from <= last.second + CONTINUE_SECONDS && from >= last.first) {
            sessions[sessions.size - 1] = last.first to maxOf(last.second, to)
        } else {
            sessions += from to to
        }
        return sessions.joinToString(";") { "${format(it.first)}-${format(it.second)}" }
    }

    private fun format(seconds: Long): String {
        val s = seconds.coerceIn(0, 24 * 3600L - 1)
        return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }
}
