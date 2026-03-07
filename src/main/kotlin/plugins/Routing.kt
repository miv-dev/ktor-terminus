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
import miv.dev.ru.asp.AspRulesEngine
import miv.dev.ru.domain.*
import miv.dev.ru.forms.FormRepository
import miv.dev.ru.session.FormSession
import miv.dev.ru.session.SessionRegistry
import miv.dev.ru.signals.SignalDispatcher
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("Routing")
private val json = Json { ignoreUnknownKeys = true }

fun Application.configureRouting(
    repo: FormRepository,
    rulesEngine: AspRulesEngine,
    signalDispatcher: SignalDispatcher,
    sessionRegistry: SessionRegistry
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
        staticResources("/", "static")

        route("/api/forms") {

            // POST /api/forms — create new form
            post {
                runCatching {
                    val schema = call.receive<FormSchema>()
                    repo.save(schema)
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
                if (schema == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Form not found: $id"))
                else call.respond(schema)
            }

            // PUT /api/forms/{id}
            put("/{id}") {
                val id = call.parameters["id"]!!
                runCatching {
                    val updated = call.receive<FormSchema>()
                    val existing = repo.findById(id)
                    val message = if (existing != null) repo.generateUpdateMessage(existing, updated)
                    else "Update form $id"
                    repo.update(updated, message)
                    call.respond(HttpStatusCode.OK, mapOf("id" to id, "message" to message))
                }.onFailure { e ->
                    log.error("PUT /api/forms/$id failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // GET /api/forms/{id}/rules
            get("/{id}/rules") {
                val id = call.parameters["id"]!!
                val rules = repo.getRuleSet(id)
                if (rules == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "RuleSet not found: $id"))
                else call.respond(rules)
            }

            // PUT /api/forms/{id}/rules
            put("/{id}/rules") {
                val id = call.parameters["id"]!!
                runCatching {
                    val rules = call.receive<RuleSet>()
                    repo.updateRuleSet(rules, "Update rules for $id")
                    call.respond(HttpStatusCode.OK, mapOf("id" to id))
                }.onFailure { e ->
                    log.error("PUT /api/forms/$id/rules failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // GET /api/forms/{id}/history
            get("/{id}/history") {
                runCatching {
                    call.respond(repo.getHistory())
                }.onFailure { e ->
                    log.error("GET history failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // GET /api/forms/{id}/diff?from={c1}&to={c2}
            get("/{id}/diff") {
                val id = call.parameters["id"]!!
                val from = call.request.queryParameters["from"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'from'"))
                val to = call.request.queryParameters["to"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'to'"))
                runCatching {
                    call.respond(repo.getDiff(id, from, to))
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
                        ?: return@runCatching call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Commit not found: $commitId")
                        )
                    val rules = repo.getRuleSet(id)
                    if (rules != null) {
                        val signals = rulesEngine.evaluate(schema, rules, emptyMap())
                        signalDispatcher.broadcast(id, signals)
                    }
                    call.respond(HttpStatusCode.OK, mapOf("checkedOut" to commitId, "fields" to schema.fields.size))
                }.onFailure { e ->
                    log.error("POST checkout failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // POST /api/forms/{id}/evaluate — stateless, for testing
            post("/{id}/evaluate") {
                val id = call.parameters["id"]!!
                runCatching {
                    val context = call.receive<EvaluationContext>()
                    val schema = repo.findById(id)
                        ?: return@runCatching call.respond(HttpStatusCode.NotFound, mapOf("error" to "Form not found: $id"))
                    val rules = repo.getRuleSet(id)
                        ?: return@runCatching call.respond(HttpStatusCode.NotFound, mapOf("error" to "RuleSet not found: $id"))
                    val signals = rulesEngine.evaluate(schema, rules, context.fieldValues, context.role)
                    signalDispatcher.broadcast(id, signals)
                    call.respond(signals)
                }.onFailure { e ->
                    log.error("POST evaluate failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }
        }

        // WS /ws/form/{formId} — bidirectional session
        webSocket("/ws/form/{formId}") {
            val formId = call.parameters["formId"]!!
            log.info("WS connect: $formId")

            val schema = repo.findById(formId)
            if (schema == null) {
                close(CloseReason(CloseReason.Codes.NORMAL, "Form not found"))
                return@webSocket
            }
            val rules = repo.getRuleSet(formId)
            if (rules == null) {
                close(CloseReason(CloseReason.Codes.NORMAL, "RuleSet not found"))
                return@webSocket
            }

            val sessionId = UUID.randomUUID().toString()
            val session = FormSession(sessionId, formId, schema, rules, rulesEngine, signalDispatcher)
            sessionRegistry.register(sessionId, session)

            val processorJob = session.startProcessor(this)

            // Outgoing: dispatcher → WS frames
            val outJob = launch {
                signalDispatcher.flowFor(formId).collect { signal ->
                    sendSerialized(signal)
                }
            }

            // Incoming: WS frames → session
            val inJob = launch {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        runCatching {
                            val event = json.decodeFromString<InputEvent>(frame.readText())
                            session.onInput(event)
                        }.onFailure { e ->
                            log.warn("Invalid WS event: ${e.message}")
                        }
                    }
                }
            }

            // Send initial state directly to this client, then let dispatcher handle updates
            val initialSignals = rulesEngine.evaluate(schema, rules, emptyMap())
            initialSignals.forEach { sendSerialized(it) }

            inJob.join()
            outJob.cancel()
            processorJob.cancel()
            session.close()
            sessionRegistry.unregister(sessionId)
            log.info("WS disconnect: $formId / $sessionId")
        }

        // WS /ws/form/{formId}/field/{fieldId} — single field signals
        webSocket("/ws/form/{formId}/field/{fieldId}") {
            val formId = call.parameters["formId"]!!
            val fieldId = call.parameters["fieldId"]!!
            log.info("WS field connect: $formId/$fieldId")

            val job = launch {
                signalDispatcher.flowFor(formId).collect { signal ->
                    if (signal.fieldId == fieldId) sendSerialized(signal)
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
