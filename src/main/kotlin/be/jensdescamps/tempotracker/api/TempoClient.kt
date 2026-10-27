package be.jensdescamps.tempotracker.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate

data class WorklogPayload(
    val issueId: Long,
    val authorAccountId: String,
    val timeSpentSeconds: Long,
    /** yyyy-MM-dd */
    val startDate: String,
    /** HH:mm:ss */
    val startTime: String,
    val description: String,
) {
    fun toJson(): String = JsonObject().apply {
        addProperty("issueId", issueId)
        addProperty("authorAccountId", authorAccountId)
        addProperty("timeSpentSeconds", timeSpentSeconds)
        addProperty("startDate", startDate)
        addProperty("startTime", startTime)
        addProperty("description", description)
    }.toString()
}

/** Tempo Cloud REST API v4 (https://apidocs.tempo.io). */
class TempoClient(private val token: String) {
    init {
        if (token.isBlank()) throw ApiException("Tempo API token isn't configured yet: Settings → Tools → Tempo Tracker")
    }

    /** @return the new Tempo worklog id */
    fun create(payload: WorklogPayload): Long =
        worklogId(request("POST", "/worklogs", payload.toJson()))
            ?: throw ApiException("Tempo didn't return a worklog id")

    /** Replaces an existing worklog. Throws ApiException(status = 404) if it was deleted in Tempo. */
    fun update(id: Long, payload: WorklogPayload): Long =
        worklogId(request("PUT", "/worklogs/$id", payload.toJson())) ?: id

    fun delete(id: Long) {
        try {
            request("DELETE", "/worklogs/$id", null)
        } catch (e: ApiException) {
            if (e.status != 404) throw e // already gone is fine
        }
    }

    /** Sum of everything the user has logged in Tempo on [date], from any source. */
    fun totalSeconds(accountId: String, date: LocalDate): Long {
        val user = URLEncoder.encode(accountId, StandardCharsets.UTF_8)
        val json = request("GET", "/worklogs/user/$user?from=$date&to=$date&limit=1000", null) as? JsonObject ?: return 0
        val results = json.get("results")?.takeIf { it.isJsonArray }?.asJsonArray ?: return 0
        return results.sumOf { r -> r.asJsonObject.get("timeSpentSeconds")?.asLong ?: 0L }
    }

    private fun worklogId(json: JsonElement?): Long? =
        (json as? JsonObject)?.get("tempoWorklogId")?.takeIf { it.isJsonPrimitive }?.asLong

    private fun request(method: String, path: String, body: String?): JsonElement? {
        val builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
        if (body != null) {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        val response = Http.send(builder.build())
        if (response.statusCode() !in 200..299) {
            throw ApiException(Http.errorMessage("Tempo", response), response.statusCode())
        }
        return Http.parse(response.body())
    }

    companion object {
        const val BASE_URL = "https://api.tempo.io/4"
    }
}
