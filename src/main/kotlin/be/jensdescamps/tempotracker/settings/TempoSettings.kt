package be.jensdescamps.tempotracker.settings

import be.jensdescamps.tempotracker.core.TicketParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

enum class SubmitMode(private val label: String) {
    MANUAL("Review and submit myself (daily reminder)"),
    DAILY("Submit automatically every day");

    override fun toString() = label
}

@Service(Service.Level.APP)
@State(name = "TempoTrackerSettings", storages = [Storage("tempo-tracker.xml")])
class TempoSettings : PersistentStateComponent<TempoSettings.State> {

    class State {
        var jiraBaseUrl: String = ""
        var jiraEmail: String = ""
        var branchPattern: String = TicketParser.DEFAULT_PATTERN
        /** Optional comma-separated Jira project keys, e.g. "ART01DEV10, OPS". */
        var projectKeys: String = ""
        var roundingMinutes: Int = 15
        var minimumMinutes: Int = 1
        var idleTimeoutMinutes: Int = 5
        var stopOnFocusLoss: Boolean = false
        var trackUnassigned: Boolean = true
        var submitMode: String = SubmitMode.MANUAL.name
        /** Reminder time (manual mode) or auto-submit time (daily mode), H:mm. */
        var dailyTime: String = "17:00"
        var descriptionTemplate: String = "Working on issue {key}"
        /** Place a day's worklogs one after another instead of each at its own first activity. */
        var backToBack: Boolean = false
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val mode: SubmitMode
        get() = runCatching { SubmitMode.valueOf(myState.submitMode) }.getOrDefault(SubmitMode.MANUAL)

    val projectKeyList: List<String>
        get() = myState.projectKeys.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        fun getInstance(): TempoSettings = service()
    }
}

/** API tokens live in the IDE password safe (macOS Keychain), never in XML. */
object TempoSecrets {
    private fun attributes(key: String) = CredentialAttributes(generateServiceName("Tempo Tracker", key))

    var jiraToken: String
        get() = PasswordSafe.instance.getPassword(attributes("jira-api-token")).orEmpty()
        set(value) = PasswordSafe.instance.setPassword(attributes("jira-api-token"), value.trim().ifBlank { null })

    var tempoToken: String
        get() = PasswordSafe.instance.getPassword(attributes("tempo-api-token")).orEmpty()
        set(value) = PasswordSafe.instance.setPassword(attributes("tempo-api-token"), value.trim().ifBlank { null })
}
