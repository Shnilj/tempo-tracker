package be.jensdescamps.tempotracker.api

import be.jensdescamps.tempotracker.settings.TempoSecrets
import be.jensdescamps.tempotracker.settings.TempoSettings
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class JiraCredentials(val baseUrl: String, val email: String, val token: String) {
    val isComplete get() = baseUrl.isNotBlank() && email.isNotBlank() && token.isNotBlank()
}

data class JiraUser(val accountId: String, val displayName: String)

data class JiraIssue(val id: Long, val key: String, val summary: String)

/** Minimal Jira Cloud REST v3 client (basic auth with email + API token). */
class JiraClient(credentials: JiraCredentials) {
    private val base = credentials.baseUrl.trim().trimEnd('/')
    private val auth = "Basic " + Base64.getEncoder()
        .encodeToString("${credentials.email.trim()}:${credentials.token.trim()}".toByteArray(StandardCharsets.UTF_8))

    init {
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            throw ApiException("Jira URL must start with https://, e.g. https://yourcompany.atlassian.net")
        }
    }

    fun myself(): JiraUser {
        val json = get("/rest/api/3/myself")
        return JiraUser(json.str("accountId"), json.str("displayName"))
    }

    fun issue(key: String): JiraIssue {
        val json = try {
            get("/rest/api/3/issue/${URLEncoder.encode(key, StandardCharsets.UTF_8)}?fields=summary")
        } catch (e: ApiException) {
            if (e.status == 404) throw ApiException("$key doesn't exist in Jira (or you can't see it)", 404)
            throw e
        }
        val summary = json.getAsJsonObject("fields")?.str("summary").orEmpty()
        return JiraIssue(json.str("id").toLong(), json.str("key"), summary)
    }

    private fun get(path: String): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = Http.send(request)
        if (response.statusCode() !in 200..299) {
            throw ApiException(Http.errorMessage("Jira", response), response.statusCode())
        }
        return Http.parse(response.body()) as? JsonObject
            ?: throw ApiException("Jira returned an unexpected response")
    }
}

/** Jira access with the configured credentials and a small in-memory cache. */
@Service(Service.Level.APP)
class JiraService {
    private val issues = ConcurrentHashMap<String, JiraIssue>()

    @Volatile
    private var me: JiraUser? = null

    fun client(): JiraClient {
        val s = TempoSettings.getInstance().state
        val credentials = JiraCredentials(s.jiraBaseUrl, s.jiraEmail, TempoSecrets.jiraToken)
        if (!credentials.isComplete) {
            throw ApiException("Jira isn't configured yet: Settings → Tools → Tempo Tracker")
        }
        return JiraClient(credentials)
    }

    fun myself(): JiraUser = me ?: client().myself().also { me = it }

    fun issue(key: String): JiraIssue = issues[key] ?: client().issue(key).also { issues[key] = it }

    fun clearCache() {
        issues.clear()
        me = null
    }

    companion object {
        fun getInstance(): JiraService = service()
    }
}
