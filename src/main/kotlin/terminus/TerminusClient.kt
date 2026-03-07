package miv.dev.ru.terminus

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.Base64

data class TerminusConfig(
    val url: String,
    val team: String,
    val db: String,
    val user: String,
    val password: String
)

class TerminusClient(private val config: TerminusConfig) {
    private val log = LoggerFactory.getLogger(TerminusClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val authHeader: String = "Basic " +
        Base64.getEncoder().encodeToString("${config.user}:${config.password}".toByteArray())

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(Logging) { level = LogLevel.INFO }
        defaultRequest {
            header(HttpHeaders.Authorization, authHeader)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }
    }

    suspend fun databaseExists(): Boolean {
        return try {
            val resp = http.get("${config.url}/api/db/${config.team}/${config.db}")
            resp.status.isSuccess()
        } catch (e: Exception) {
            log.warn("Could not check DB existence: ${e.message}")
            false
        }
    }

    suspend fun createDatabase() {
        val body = buildJsonObject {
            put("label", config.db)
            put("comment", "FormGraph database")
            put("public", false)
            put("schema", true)
        }
        val resp = http.post("${config.url}/api/db/${config.team}/${config.db}") {
            setBody(body.toString())
        }
        check(resp.status.isSuccess()) {
            "Failed to create DB: ${resp.status} — ${resp.bodyAsText()}"
        }
        log.info("Created TerminusDB database: ${config.db}")
    }

    suspend fun insertDocuments(docs: List<JsonObject>, graph: String = "instance") {
        val body = buildJsonArray { docs.forEach { add(it) } }
        val resp = http.post("${config.url}/api/document/${config.team}/${config.db}") {
            parameter("graph_type", graph)
            parameter("author", "formgraph")
            parameter("message", "Insert documents")
            setBody(body.toString())
        }
        check(resp.status.isSuccess()) {
            "Failed to insert documents: ${resp.status} — ${resp.bodyAsText()}"
        }
    }

    suspend fun insertDocumentsWithMessage(docs: List<JsonObject>, message: String) {
        val body = buildJsonArray { docs.forEach { add(it) } }
        val resp = http.post("${config.url}/api/document/${config.team}/${config.db}") {
            parameter("graph_type", "instance")
            parameter("author", "formgraph")
            parameter("message", message)
            setBody(body.toString())
        }
        check(resp.status.isSuccess()) {
            "Failed to insert documents: ${resp.status} — ${resp.bodyAsText()}"
        }
    }

    suspend fun replaceDocuments(docs: List<JsonObject>, message: String) {
        val body = buildJsonArray { docs.forEach { add(it) } }
        val resp = http.put("${config.url}/api/document/${config.team}/${config.db}") {
            parameter("graph_type", "instance")
            parameter("author", "formgraph")
            parameter("message", message)
            parameter("create", "true")  // upsert: insert if not found
            setBody(body.toString())
        }
        check(resp.status.isSuccess()) {
            "Failed to replace documents: ${resp.status} — ${resp.bodyAsText()}"
        }
    }

    suspend fun getDocument(id: String): JsonObject? {
        val resp = http.get("${config.url}/api/document/${config.team}/${config.db}") {
            parameter("id", id)
        }
        if (resp.status == HttpStatusCode.NotFound) return null
        check(resp.status.isSuccess()) {
            "Failed to get document $id: ${resp.status} — ${resp.bodyAsText()}"
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    suspend fun deleteDocument(id: String) {
        val resp = http.delete("${config.url}/api/document/${config.team}/${config.db}") {
            parameter("id", id)
            parameter("author", "formgraph")
            parameter("message", "Delete $id")
        }
        check(resp.status.isSuccess()) {
            "Failed to delete document $id: ${resp.status} — ${resp.bodyAsText()}"
        }
    }

    suspend fun woqlQuery(query: JsonObject): JsonObject {
        val body = buildJsonObject { put("query", query) }
        val resp = http.post("${config.url}/api/woql/${config.team}/${config.db}") {
            setBody(body.toString())
        }
        check(resp.status.isSuccess()) {
            "WOQL query failed: ${resp.status} — ${resp.bodyAsText()}"
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    suspend fun getCommitLog(): JsonArray {
        val resp = http.get("${config.url}/api/log/${config.team}/${config.db}") {
            parameter("count", 100)
        }
        check(resp.status.isSuccess()) {
            "Failed to get commit log: ${resp.status} — ${resp.bodyAsText()}"
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonArray
    }

    suspend fun getDiff(before: String, after: String): JsonObject {
        fun commitPath(id: String) = "${config.team}/${config.db}/local/commit/$id"
        val body = buildJsonObject {
            put("before_data_version", commitPath(before))
            put("after_data_version", commitPath(after))
        }
        val resp = http.post("${config.url}/api/diff") {
            setBody(body.toString())
        }
        if (!resp.status.isSuccess()) {
            log.warn("Diff failed: ${resp.status} — ${resp.bodyAsText()}")
            return buildJsonObject { put("error", resp.bodyAsText()) }
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    suspend fun pushSchemaAll(docs: JsonArray) {
        val resp = http.post("${config.url}/api/document/${config.team}/${config.db}") {
            parameter("graph_type", "schema")
            parameter("author", "formgraph")
            parameter("message", "Initialize schema")
            setBody(docs.toString())
        }
        check(resp.status.isSuccess()) {
            "Schema push failed: ${resp.status} — ${resp.bodyAsText()}"
        }
        log.info("Schema pushed: ${docs.size} class definitions")
    }

    suspend fun getDocumentAtCommit(commitId: String, docId: String): JsonObject? {
        val resp = http.get("${config.url}/api/document/${config.team}/${config.db}/local/commit/$commitId") {
            parameter("id", docId)
        }
        if (resp.status == HttpStatusCode.NotFound) return null
        if (!resp.status.isSuccess()) {
            log.warn("getDocumentAtCommit $docId@$commitId: ${resp.status}")
            return null
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    suspend fun queryDocumentsAtCommit(commitId: String, type: String): JsonArray {
        val resp = http.get("${config.url}/api/document/${config.team}/${config.db}/local/commit/$commitId") {
            parameter("type", type)
            parameter("as_list", true)
        }
        if (!resp.status.isSuccess()) {
            log.warn("queryDocumentsAtCommit $type@$commitId: ${resp.status}")
            return buildJsonArray { }
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonArray
    }

    suspend fun queryDocuments(type: String): JsonArray {
        val resp = http.get("${config.url}/api/document/${config.team}/${config.db}") {
            parameter("type", type)
            parameter("as_list", true)
        }
        check(resp.status.isSuccess()) {
            "Failed to query documents of type $type: ${resp.status} — ${resp.bodyAsText()}"
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonArray
    }

    fun close() = http.close()
}
