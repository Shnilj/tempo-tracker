package be.jensdescamps.tempotracker.settings

import be.jensdescamps.tempotracker.api.JiraClient
import be.jensdescamps.tempotracker.api.JiraCredentials
import be.jensdescamps.tempotracker.api.JiraService
import be.jensdescamps.tempotracker.api.TempoClient
import be.jensdescamps.tempotracker.core.Durations
import be.jensdescamps.tempotracker.core.TicketParser
import be.jensdescamps.tempotracker.core.TimeStoreListener
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

    override fun createPanel(): DialogPanel {
        jiraToken = TempoSecrets.jiraToken
        tempoToken = TempoSecrets.tempoToken
        mode = settings.mode

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
                row("Submitting:") {
                    comboBox(SubmitMode.entries).bindItem(::mode)
                }
                row("Daily at:") {
                    textField().bindText(s::dailyTime).columns(COLUMNS_SHORT)
                        .comment("Manual mode: reminder if something isn't logged yet. Automatic mode: submits today and fixes up the last 7 days.")
                        .validationOnApply { if (Durations.parseClock(it.text) != null) null else error("Use HH:mm, e.g. 17:00") }
                }
            }
        }
    }

    override fun apply() {
        super.apply()
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
}
