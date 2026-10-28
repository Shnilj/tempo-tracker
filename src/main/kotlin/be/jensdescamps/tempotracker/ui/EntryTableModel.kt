package be.jensdescamps.tempotracker.ui

import be.jensdescamps.tempotracker.core.Durations
import be.jensdescamps.tempotracker.core.LogPolicy
import be.jensdescamps.tempotracker.core.TimeEntry
import be.jensdescamps.tempotracker.core.TimeStore
import be.jensdescamps.tempotracker.settings.TempoSettings
import com.intellij.util.ui.JBUI
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

class EntryTableModel : AbstractTableModel() {
    private var entries: List<TimeEntry> = emptyList()
    private var settings = TempoSettings.State()
    private var plan: Map<String, List<be.jensdescamps.tempotracker.core.Segment>> = emptyMap()

    fun setEntries(list: List<TimeEntry>, settings: TempoSettings.State) {
        entries = list
        this.settings = settings
        plan = LogPolicy.plan(list, settings)
        fireTableDataChanged()
    }

    fun entryAt(row: Int): TimeEntry? = entries.getOrNull(row)

    fun indexOf(id: String): Int = entries.indexOfFirst { it.id == id }

    override fun getRowCount() = entries.size

    override fun getColumnCount() = COLUMNS.size

    override fun getColumnName(column: Int) = COLUMNS[column]

    override fun getColumnClass(column: Int): Class<*> =
        if (column == LOG) Boolean::class.javaObjectType else String::class.java

    override fun isCellEditable(row: Int, column: Int): Boolean {
        val e = entries[row]
        return when (column) {
            LOG, TO_LOG, DESCRIPTION -> e.ticket.isNotBlank()
            TICKET -> e.worklogId == 0L
            else -> false
        }
    }

    override fun getValueAt(row: Int, column: Int): Any {
        val e = entries[row]
        return when (column) {
            LOG -> !e.excluded && e.ticket.isNotBlank()
            TICKET -> e.ticket.ifBlank { NO_TICKET }
            SUMMARY -> e.summary
            TRACKED -> if (e.ritualId.isNotEmpty()) "meeting" else Durations.format(e.trackedSeconds)
            TO_LOG -> Durations.format(
                if (LogPolicy.usesTimeline(e, settings) && !e.excluded) LogPolicy.plannedSeconds(e, settings, plan[e.id])
                else LogPolicy.secondsToLog(e.copy(excluded = false), settings),
            ) +
                if (e.overrideSeconds >= 0) " ✎" else ""
            TIME -> timeRange(e)
            DESCRIPTION -> if (e.ticket.isBlank()) "" else LogPolicy.description(e, settings)
            else -> LogPolicy.status(e, settings, plan[e.id])
        }
    }

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        val e = entries.getOrNull(row) ?: return
        val store = TimeStore.getInstance()
        when (column) {
            LOG -> store.update(e.id) {
                it.excluded = value != true
                it.lastError = ""
            }
            TICKET -> {
                val ticket = value?.toString()?.trim()?.uppercase().orEmpty()
                if (ticket.isNotEmpty() && ticket != NO_TICKET.uppercase()) store.reassign(e.id, ticket)
            }
            TO_LOG -> {
                val text = value?.toString()?.replace("✎", "")?.trim().orEmpty()
                if (text.isEmpty()) {
                    store.update(e.id) { it.overrideSeconds = -1 } // back to rounded tracked time
                } else {
                    Durations.parse(text)?.let { seconds -> store.update(e.id) { it.overrideSeconds = seconds } }
                }
            }
            DESCRIPTION -> {
                val text = value?.toString()?.trim().orEmpty()
                val fromTemplate = LogPolicy.description(e.copy(description = ""), settings)
                store.update(e.id) { it.description = if (text == fromTemplate) "" else text }
            }
        }
    }

    /** "08:30–09:30, 09:45–10:15" for entries that will be logged. */
    private fun timeRange(e: TimeEntry): String =
        if (LogPolicy.plannedSeconds(e, settings, plan[e.id]) <= 0) "" else LogPolicy.timeRange(plan[e.id])

    fun applyColumnWidths(table: JTable) {
        val widths = intArrayOf(36, 140, 220, 70, 80, 100, 220, 130)
        widths.forEachIndexed { i, w -> table.columnModel.getColumn(i).preferredWidth = JBUI.scale(w) }
        table.columnModel.getColumn(LOG).maxWidth = JBUI.scale(44)
    }

    companion object {
        const val LOG = 0
        const val TICKET = 1
        const val SUMMARY = 2
        const val TRACKED = 3
        const val TO_LOG = 4
        const val TIME = 5
        const val DESCRIPTION = 6
        const val STATUS = 7
        private val COLUMNS = arrayOf("Log", "Ticket", "Summary", "Tracked", "To log", "Time", "Description", "Status")
        const val NO_TICKET = "(no ticket)"
    }
}
