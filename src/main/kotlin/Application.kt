package miv.dev.ru

import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import miv.dev.ru.domain.EvaluationContext
import miv.dev.ru.forms.FormRepository
import miv.dev.ru.forms.FormRulesEngine
import miv.dev.ru.forms.SeedData
import miv.dev.ru.plugins.*
import miv.dev.ru.signals.SignalDispatcher
import miv.dev.ru.terminus.SchemaMapper
import miv.dev.ru.terminus.TerminusClient
import miv.dev.ru.terminus.TerminusConfig
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val log = LoggerFactory.getLogger("Application")

    val terminusConfig = TerminusConfig(
        url = environment.config.propertyOrNull("terminus.url")?.getString() ?: "http://localhost:6363",
        team = environment.config.propertyOrNull("terminus.team")?.getString() ?: "admin",
        db = environment.config.propertyOrNull("terminus.db")?.getString() ?: "formgraph",
        user = environment.config.propertyOrNull("terminus.user")?.getString() ?: "admin",
        password = environment.config.propertyOrNull("terminus.password")?.getString() ?: "root"
    )

    val terminusClient = TerminusClient(terminusConfig)
    val formRepo = FormRepository(terminusClient)
    val rulesEngine = FormRulesEngine(terminusClient)
    val signalDispatcher = SignalDispatcher()

    configureSerialization()
    configureWebSockets()
    configureRouting(formRepo, rulesEngine, signalDispatcher)

    val appScope = CoroutineScope(Dispatchers.IO)

    monitor.subscribe(ApplicationStarted) {
        appScope.launch {
            try {
                initializeTerminus(terminusClient, formRepo, rulesEngine, signalDispatcher, log)
            } catch (e: Exception) {
                log.error("TerminusDB initialization failed — app will run in degraded mode", e)
            }
        }
    }

    monitor.subscribe(ApplicationStopped) {
        terminusClient.close()
    }
}

private suspend fun initializeTerminus(
    client: TerminusClient,
    repo: FormRepository,
    rulesEngine: FormRulesEngine,
    dispatcher: SignalDispatcher,
    log: org.slf4j.Logger
) {
    val exists = client.databaseExists()
    if (!exists) {
        log.info("TerminusDB database not found — creating and seeding...")
        client.createDatabase()

        // Push OWL schema — all class definitions in one batch
        val schemaDocs = SchemaMapper.buildTerminusSchema()
        client.pushSchemaAll(schemaDocs)
        log.info("Schema pushed to TerminusDB")

        // Seed v1
        repo.save(SeedData.clientFormV1)
        log.info("Seeded client_form v1")

        // Seed v2 migration
        repo.update(
            SeedData.clientFormV2,
            "Migration v2: added phone field, email now optional for юрлицо"
        )
        log.info("Seeded client_form v2 migration")
    } else {
        log.info("TerminusDB database found — skipping seed")
    }

    // Broadcast initial signals with empty context
    val schema = repo.findById("client_form")
    if (schema != null) {
        val signals = rulesEngine.evaluate(schema, EvaluationContext())
        dispatcher.broadcast("client_form", signals)
        log.info("Initial signals broadcast for client_form (${signals.size} fields)")
    }
}
