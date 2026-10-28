package be.jensdescamps.tempotracker.settings

import be.jensdescamps.tempotracker.api.JiraClient
import be.jensdescamps.tempotracker.api.JiraCredentials
import be.jensdescamps.tempotracker.api.JiraService
import be.jensdescamps.tempotracker.api.TempoClient
import be.jensdescamps.tempotracker.core.Durations
import be.jensdescamps.tempotracker.core.TicketParser
import be.jensdescamps.tempotracker.core.TimeStore
import be.jensdescamps.tempotracker.core.TimeStoreListener
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import javax.swing.table.AbstractTableModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import java.time.LocalDate
import javax.swing.JLabel

class TempoConfigurable : BoundConfigurable("Tempo Tracker") {
    private val settings = TempoSettings.getInstance()
    private val s = settings.state

    // Secrets and the enum aren't plain state fields, so they're bound to these and saved in apply().
    private var jiraToken = ""
    private var tempoToken = ""
    private var mode: SubmitMode? = settings.mode

    private lateinit var urlField: JBTextField
    private lateinit var emailField: JBTextField
    private lateinit var jiraTokenField: JBPasswordField
    private lateinit var tempoTokenField: JBPasswordField
    private lateinit var testResult: JLabel

    private val ritualModel = RitualTableModel()
    private val ritualTable = JBTable(ritualModel).apply {
        setShowGrid(false)
        emptyText.text = "No rituals yet: add your standup, retro, planning…"
    }

    override fun createPanel(): DialogPanel {
        jiraToken = TempoSecrets.jiraToken
        tempoToken = TempoSecrets.tempoToken
        mode = settings.mode
        ritualModel.setItems(s.rituals)

        return panel {
            group("Jira Cloud") {
                row("Site URL:") {
                    textField().bindText(s::jiraBaseUrl).columns(COLUMNS_LARGE)
                        .comment("For example https://yourcompany.atlassian.net")
                        .applyToComponent { urlField = this }
                }
                row("Email:") {
                    textField().bindText(s::jiraEmail).columns(COLUMNS_LARGE)
                        .applyToComponent { emailField = this }
                }
                row("API token:") {
                    passwordField().bindText(::jiraToken).columns(COLUMNS_LARGE)
                        .comment("Create one at <a href=\"https://id.atlassian.com/manage-profile/security/api-tokens\">id.atlassian.com → Security → API tokens</a>")
                        .applyToComponent { jiraTokenField = this }
                }
            }
            group("Tempo") {
                row("API token:") {
                    passwordField().bindText(::tempoToken).columns(COLUMNS_LARGE)
                        .comment("In Jira: Tempo → Settings → API Integration → New Token (scope: manage worklogs)")
                        .applyToComponent { tempoTokenField = this }
                }
                row {
                    button("Test Connection") { testConnection() }
                    testResult = label("").component
                }
            }
            group("Tracking") {
                row("Ticket pattern:") {
                    textField().bindText(s::branchPattern).columns(COLUMNS_LARGE)
                        .comment("Regex; group 1 (or the whole match) is the ticket key. Default matches ART01DEV10-4466 in feature/ART01DEV10-4466-this-is-a-test")
                        .validationOnApply { if (TicketParser.isValidPattern(it.text)) null else error("Not a valid regular expression") }
                }
                row("Only these projects:") {
                    textField().bindText(s::projectKeys).columns(COLUMNS_LARGE)
                        .comment("Optional, comma-separated Jira project keys, e.g. ART01DEV10. Empty = any key that matches the pattern.")
                }
                row("Idle after (minutes):") {
                    intTextField(1..120).bindIntText(s::idleTimeoutMinutes).columns(COLUMNS_SHORT)
                        .comment("No keyboard or mouse activity in IntelliJ for this long stops the clock")
                }
                row {
                    checkBox("Stop counting as soon as IntelliJ loses focus").bindSelected(s::stopOnFocusLoss)
                        .comment("Off: time spent briefly in the browser or Jira still counts until the idle timeout")
                }
                row {
                    checkBox("Also track time on branches without a ticket (assign it later)").bindSelected(s::trackUnassigned)
                }
            }
            group("Logging") {
                row("Round up to (minutes):") {
                    intTextField(0..120).bindIntText(s::roundingMinutes).columns(COLUMNS_SHORT)
                        .comment("Per ticket per day; 15 = next quarter hour, 0 = no rounding")
                }
                row("Ignore less than (minutes):") {
                    intTextField(0..120).bindIntText(s::minimumMinutes).columns(COLUMNS_SHORT)
                        .comment("Avoids a 15-minute worklog for a branch you only glanced at")
                }
                row("Description:") {
                    textField().bindText(s::descriptionTemplate).columns(COLUMNS_LARGE)
                        .comment("Placeholders: {key}, {summary}")
                }
                row {
                    checkBox("Place each day's worklogs back to back").bindSelected(s::backToBack)
                        .comment("The day starts at your first activity (rounded), then each ticket follows the previous one, " +
                            "in the order you first worked on them. Off: each worklog starts at your first activity on that ticket.")
                }
                row("Submitting:") {
                    comboBox(SubmitMode.entries).bindItem(::mode)
                }
                row("Daily at:") {
                    textField().bindText(s::dailyTime).columns(COLUMNS_SHORT)
                        .comment("Manual mode: reminder if something isn't logged yet. Automatic mode: submits today and fixes up the last 7 days.")
                        .validationOnApply { if (Durations.parseClock(it.text) != null) null else error("Use HH:mm, e.g. 17:00") }
                }
            }
            group("Rituals") {
                row {
                    comment(
                        "Recurring meetings, logged at a fixed time on their own ticket, on days you've worked in IntelliJ. " +
                            "IDE time isn't counted while one is running, and back-to-back worklogs flow around them. " +
                            "Days: Mon-Fri, Mon,Wed,Thu,Fri or ma,wo,do,vr.",
                    )
                }
                row {
                    val decorated = ToolbarDecorator.createDecorator(ritualTable)
                        .setAddAction { addRitual() }
                        .setRemoveAction { removeRitual() }
                        .disableUpDownActions()
                        .createPanel()
                    decorated.preferredSize = JBUI.size(600, 150)
                    cell(decorated).align(Align.FILL)
                }
            }
        }
    }

    override fun isModified(): Boolean = super.isModified() || ritualModel.items != s.rituals

    override fun reset() {
        super.reset()
        ritualModel.setItems(s.rituals)
    }

    override fun apply() {
        if (ritualTable.isEditing) ritualTable.cellEditor?.stopCellEditing()
        ritualModel.items.firstNotNullOfOrNull { it.validationError() }?.let { throw ConfigurationException(it) }
        super.apply()
        s.rituals = ritualModel.items.map { it.copy() }.toMutableList()
        val today = LocalDate.now()
        if (TimeStore.getInstance().hasActivity(today)) TimeStore.getInstance().ensureRituals(today, s.rituals)
        s.submitMode = (mode ?: SubmitMode.MANUAL).name
        TempoSecrets.jiraToken = jiraToken
        TempoSecrets.tempoToken = tempoToken
        JiraService.getInstance().clearCache()
        TimeStoreListener.publish()
    }

    private fun testConnection() {
        val credentials = JiraCredentials(urlField.text, emailField.text, String(jiraTokenField.password))
        val tempo = String(tempoTokenField.password)
        testResult.text = "Testing…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val message = try {
                val me = JiraClient(credentials).myself()
                TempoClient(tempo).totalSeconds(me.accountId, LocalDate.now())
                "✓ Connected to Jira and Tempo as ${me.displayName}"
            } catch (e: Exception) {
                "✗ ${e.message}"
            }
            ApplicationManager.getApplication().invokeLater({ testResult.text = message }, ModalityState.any())
        }
    }

    private fun addRitual() {
        if (ritualTable.isEditing) ritualTable.cellEditor?.stopCellEditing()
        ritualModel.add(Ritual())
        val row = ritualModel.rowCount - 1
        ritualTable.selectionModel.setSelectionInterval(row, row)
        ritualTable.editCellAt(row, RitualTableModel.NAME)
    }

    private fun removeRitual() {
        if (ritualTable.isEditing) ritualTable.cellEditor?.stopCellEditing()
        ritualTable.selectedRows.sortedDescending().forEach { ritualModel.removeAt(it) }
    }
}

private class RitualTableModel : AbstractTableModel() {
    val items = mutableListOf<Ritual>()

    fun setItems(rituals: List<Ritual>) {
        items.clear()
        items += rituals.map { it.copy() }
        fireTableDataChanged()
    }

    fun add(ritual: Ritual) {
        items += ritual
        fireTableRowsInserted(items.size - 1, items.size - 1)
    }

    fun removeAt(row: Int) {
        if (row !in items.indices) return
        items.removeAt(row)
        fireTableRowsDeleted(row, row)
    }

    override fun getRowCount() = items.size

    override fun getColumnCount() = COLUMNS.size

    override fun getColumnName(column: Int) = COLUMNS[column]

    override fun getColumnClass(column: Int): Class<*> = when (column) {
        ENABLED -> Boolean::class.javaObjectType
        MINUTES -> Int::class.javaObjectType
        else -> String::class.java
    }

    override fun isCellEditable(row: Int, column: Int) = true

    override fun getValueAt(row: Int, column: Int): Any {
        val r = items[row]
        return when (column) {
            ENABLED -> r.enabled
            NAME -> r.name
            TICKET -> r.ticket
            DAYS -> r.days
            START -> r.start
            else -> r.minutes
        }
    }

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        val r = items.getOrNull(row) ?: return
        val text = value?.toString()?.trim().orEmpty()
        when (column) {
            ENABLED -> r.enabled = value == true
            NAME -> r.name = text
            TICKET -> r.ticket = text.uppercase()
            DAYS -> r.days = text
            START -> r.start = text
            MINUTES -> r.minutes = (value as? Int) ?: text.toIntOrNull() ?: r.minutes
        }
        fireTableCellUpdated(row, column)
    }

    companion object {
        const val ENABLED = 0
        const val NAME = 1
        const val TICKET = 2
        const val DAYS = 3
        const val START = 4
        const val MINUTES = 5
        private val COLUMNS = arrayOf("On", "Name", "Ticket", "Days", "Start", "Minutes")
    }
}
