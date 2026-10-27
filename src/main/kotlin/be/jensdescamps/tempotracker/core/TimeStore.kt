package be.jensdescamps.tempotracker.core

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

    fun credit(date: LocalDate, ticket: String, seconds: Long, startTime: String) = write {
        val d = date.toString()
        val entry = data.entries.firstOrNull { it.date == d && it.ticket == ticket }
            ?: TimeEntry(date = d, ticket = ticket, startTime = startTime).also { data.entries.add(it) }
        entry.trackedSeconds += seconds
    }

    /** Adds manually entered time; merges into an existing entry for the same ticket and day. */
    fun addManual(date: LocalDate, ticket: String, seconds: Long) = write {
        val settings = TempoSettings.getInstance().state
        val d = date.toString()
        val existing = data.entries.firstOrNull { it.date == d && it.ticket == ticket }
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
        val target = data.entries.firstOrNull { it !== entry && it.date == entry.date && it.ticket == newTicket }
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
