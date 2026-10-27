package be.jensdescamps.tempotracker.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ApiException(message: String, val status: Int = -1) : Exception(message)

internal object Http {
    // ProxySelector.getDefault() follows the IDE's HTTP proxy settings.
    private val client: HttpClient by lazy {
        val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        ProxySelector.getDefault()?.let { builder.proxy(it) }
        builder.build()
    }

    fun send(request: HttpRequest): HttpResponse<String> =
        try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw ApiException("Network error: ${e.message ?: e.javaClass.simpleName}")
        }

    fun parse(body: String?): JsonElement? =
        if (body.isNullOrBlank()) null else runCatching { JsonParser.parseString(body) }.getOrNull()

    fun errorMessage(service: String, response: HttpResponse<String>): String {
        val code = response.statusCode()
        val details = mutableListOf<String>()
        (parse(response.body()) as? JsonObject)?.let { o ->
            o.get("errors")?.let { errors ->
                when {
                    errors.isJsonArray -> errors.asJsonArray.forEach { e ->
                        if (e.isJsonObject) e.asJsonObject.str("message").takeIf { it.isNotBlank() }?.let(details::add)
                        else if (e.isJsonPrimitive) details.add(e.asString)
                    }
                    errors.isJsonObject -> errors.asJsonObject.entrySet().forEach { (k, v) ->
                        details.add("$k: ${if (v.isJsonPrimitive) v.asString else v.toString()}")
                    }
                    else -> {}
                }
            }
            o.get("errorMessages")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { if (it.isJsonPrimitive) details.add(it.asString) }
            o.str("message").takeIf { it.isNotBlank() }?.let(details::add)
        }
        val hint = when (code) {
            401 -> "authentication failed, check your token"
            403 -> "permission denied"
            404 -> "not found"
            else -> ""
        }
        val sb = StringBuilder("$service error $code")
        if (hint.isNotEmpty()) sb.append(" ($hint)")
        if (details.isNotEmpty()) sb.append(": ").append(details.joinToString("; "))
        return sb.toString()
    }
}

internal fun JsonObject.str(name: String): String {
    val e = get(name) ?: return ""
    return if (e.isJsonPrimitive) e.asString else ""
}
