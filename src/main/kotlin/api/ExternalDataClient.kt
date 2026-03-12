package miv.dev.ru.api

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import miv.dev.ru.domain.DataSource
import miv.dev.ru.domain.SubmitAction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class ExternalDataClient {
    private val log = LoggerFactory.getLogger(ExternalDataClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, List<String>>()

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 6_000
            connectTimeoutMillis = 4_000
        }
    }

    suspend fun fetchOptions(source: DataSource, triggerValue: String): List<String> {
        val cacheKey = "${source.url}|${source.triggerField}|$triggerValue"
        cache[cacheKey]?.let { return it }

        return try {
            val resp = when (source.method.uppercase()) {
                "GET" -> http.get(source.url) {
                    if (source.queryParam.isNotBlank()) parameter(source.queryParam, triggerValue)
                }
                else -> http.post(source.url) {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        if (source.bodyParam.isNotBlank()) put(source.bodyParam, triggerValue)
                    }.toString())
                }
            }
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val options = extractPath(body, source.responsePath)
                ?.map { it.jsonPrimitive.content }
                ?.sorted()
                ?: emptyList()
            cache[cacheKey] = options
            log.info("Fetched ${options.size} options from ${source.url} (trigger: $triggerValue)")
            options
        } catch (e: Exception) {
            log.warn("ExternalDataClient failed [${source.url}]: ${e.message}")
            emptyList()
        }
    }

    private fun extractPath(obj: JsonObject, path: String): JsonArray? {
        var current: JsonElement = obj
        for (key in path.split(".")) {
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        return current as? JsonArray
    }

    data class SubmitResult(val success: Boolean, val statusCode: Int, val body: String)

    suspend fun submitForm(action: SubmitAction, values: Map<String, String>): SubmitResult {
        return try {
            val body = buildJsonObject { values.forEach { (k, v) -> put(k, v) } }.toString()
            val resp = when (action.method.uppercase()) {
                "GET" -> http.get(action.url) {
                    action.headers.forEach { (k, v) -> header(k, v) }
                    values.forEach { (k, v) -> parameter(k, v) }
                }
                else -> http.post(action.url) {
                    contentType(ContentType.Application.Json)
                    action.headers.forEach { (k, v) -> header(k, v) }
                    setBody(body)
                }
            }
            val respBody = resp.bodyAsText()
            log.info("Form submitted to ${action.url}: ${resp.status}")
            SubmitResult(resp.status.isSuccess(), resp.status.value, respBody)
        } catch (e: Exception) {
            log.warn("Form submit failed [${action.url}]: ${e.message}")
            SubmitResult(false, 0, e.message ?: "unknown error")
        }
    }

    fun close() = http.close()
}
