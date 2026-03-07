package miv.dev.ru.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import miv.dev.ru.domain.*
import miv.dev.ru.forms.FormRepository
import miv.dev.ru.forms.FormRulesEngine
import miv.dev.ru.signals.SignalDispatcher
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Routing")

fun Application.configureRouting(
    repo: FormRepository,
    rulesEngine: FormRulesEngine,
    dispatcher: SignalDispatcher
) {
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }
    install(DefaultHeaders)

    routing {
        // Serve static files
        staticResources("/", "static")

        route("/api/forms") {

            // POST /api/forms — create new form schema
            post {
                runCatching {
                    val schema = call.receive<FormSchema>()
                    repo.save(schema)
                    val signals = rulesEngine.evaluate(schema, EvaluationContext())
                    dispatcher.broadcast(schema.id, signals)
                    call.respond(HttpStatusCode.Created, mapOf("id" to schema.id))
                }.onFailure { e ->
                    log.error("POST /api/forms failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // GET /api/forms/{id}
            get("/{id}") {
                val id = call.parameters["id"]!!
                val schema = repo.findById(id)
                if (schema == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Form not found: $id"))
                } else {
                    call.respond(schema)
                }
            }

            // PUT /api/forms/{id}
            put("/{id}") {
                val id = call.parameters["id"]!!
                runCatching {
                    val updated = call.receive<FormSchema>()
                    val existing = repo.findById(id)
                    val message = if (existing != null) {
                        repo.generateUpdateMessage(existing, updated)
                    } else {
                        "Update form $id"
                    }
                    repo.update(updated, message)
                    val signals = rulesEngine.evaluate(updated, EvaluationContext())
                    dispatcher.broadcast(id, signals)
                    call.respond(HttpStatusCode.OK, mapOf("id" to id, "message" to message))
                }.onFailure { e ->
                    log.error("PUT /api/forms/$id failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // GET /api/forms/{id}/history
            get("/{id}/history") {
                runCatching {
                    val history = repo.getHistory()
                    call.respond(history)
                }.onFailure { e ->
                    log.error("GET history failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // GET /api/forms/{id}/diff?from={c1}&to={c2}
            get("/{id}/diff") {
                val from = call.request.queryParameters["from"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'from'"))
                val to = call.request.queryParameters["to"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'to'"))
                runCatching {
                    val diff = repo.getDiff(from, to)
                    call.respond(diff)
                }.onFailure { e ->
                    log.error("GET diff failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // POST /api/forms/{id}/checkout/{commitId}
            post("/{id}/checkout/{commitId}") {
                val id = call.parameters["id"]!!
                val commitId = call.parameters["commitId"]!!
                runCatching {
                    val schema = repo.checkout(id, commitId)
                    if (schema == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Commit not found: $commitId"))
                        return@runCatching
                    }
                    val signals = rulesEngine.evaluate(schema, EvaluationContext())
                    dispatcher.broadcast(id, signals)
                    call.respond(HttpStatusCode.OK, mapOf(
                        "checkedOut" to commitId,
                        "fields" to schema.fields.size
                    ))
                }.onFailure { e ->
                    log.error("POST checkout failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // POST /api/forms/{id}/evaluate
            post("/{id}/evaluate") {
                val id = call.parameters["id"]!!
                runCatching {
                    val context = call.receive<EvaluationContext>()
                    val schema = repo.findById(id)
                        ?: return@runCatching call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Form not found: $id")
                        )
                    val signals = rulesEngine.evaluate(schema, context)
                    dispatcher.broadcast(id, signals)
                    call.respond(signals)
                }.onFailure { e ->
                    log.error("POST evaluate failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }
        }

        // WebSocket: all fields for a form
        webSocket("/ws/form/{formId}") {
            val formId = call.parameters["formId"]!!
            log.info("WS connection for form $formId")

            // Emit current state immediately
            dispatcher.lastKnownState(formId)?.let { state ->
                val json = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(FieldSignal.serializer()), state)
                send(Frame.Text(json))
            }

            val job = launch {
                dispatcher.subscribeForm(formId).collect { signals ->
                    val json = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(FieldSignal.serializer()), signals)
                    send(Frame.Text(json))
                }
            }

            try {
                for (frame in incoming) {
                    // Ignore incoming frames — this is a push-only channel
                }
            } finally {
                job.cancel()
                log.info("WS connection closed for form $formId")
            }
        }

        // WebSocket: single field
        webSocket("/ws/form/{formId}/field/{fieldId}") {
            val formId = call.parameters["formId"]!!
            val fieldId = call.parameters["fieldId"]!!
            log.info("WS field connection: $formId/$fieldId")

            dispatcher.lastKnownState(formId)
                ?.find { it.fieldId == fieldId }
                ?.let { signal ->
                    val json = Json.encodeToString(FieldSignal.serializer(), signal)
                    send(Frame.Text(json))
                }

            val job = launch {
                dispatcher.subscribeForm(formId).collect { signals ->
                    signals.find { it.fieldId == fieldId }?.let { signal ->
                        val json = Json.encodeToString(FieldSignal.serializer(), signal)
                        send(Frame.Text(json))
                    }
                }
            }

            try {
                for (frame in incoming) { /* push-only */ }
            } finally {
                job.cancel()
            }
        }
    }
}

