package miv.dev.ru

import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import miv.dev.ru.asp.AspRulesEngine
import miv.dev.ru.forms.FormRepository
import miv.dev.ru.forms.SeedData
import miv.dev.ru.plugins.*
import miv.dev.ru.session.SessionRegistry
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
        url = environment.config.propertyOrNull("terminus.url")?.getString() ?: "http://127.0.0.1:6363",
        team = environment.config.propertyOrNull("terminus.team")?.getString() ?: "admin",
        db = environment.config.propertyOrNull("terminus.db")?.getString() ?: "formgraph",
        user = environment.config.propertyOrNull("terminus.user")?.getString() ?: "admin",
        password = environment.config.propertyOrNull("terminus.password")?.getString() ?: "root"
    )

    val clingoPath = environment.config.propertyOrNull("asp.clingo_path")?.getString() ?: "clingo"

    val terminusClient = TerminusClient(terminusConfig)
    val formRepo = FormRepository(terminusClient)
    val rulesEngine = AspRulesEngine(clingoPath)
    val signalDispatcher = SignalDispatcher()
    val sessionRegistry = SessionRegistry()

    configureSerialization()
    configureWebSockets()
    configureRouting(formRepo, rulesEngine, signalDispatcher, sessionRegistry)

    val appScope = CoroutineScope(Dispatchers.IO)

    monitor.subscribe(ApplicationStarted) {
        appScope.launch {
            try {
                initializeTerminus(terminusClient, formRepo, log)
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
    log: org.slf4j.Logger
) {
    if (!client.databaseExists()) {
        log.info("TerminusDB database not found — creating and seeding...")
        client.createDatabase()

        val schemaDocs = SchemaMapper.buildTerminusSchema()
        client.pushSchemaAll(schemaDocs)
        log.info("Schema pushed to TerminusDB")

        // Seed v1: schema + rules
        repo.save(SeedData.clientFormV1)
        repo.saveRuleSet(SeedData.rulesV1)
        log.info("Seeded client_form v1 + rules")

        // Seed v2: schema + rules update
        repo.update(SeedData.clientFormV2, "Migration v2: added comment field")
        repo.updateRuleSet(SeedData.rulesV2, "Migration v2: phone always required")
        log.info("Seeded client_form v2 migration")
    } else {
        log.info("TerminusDB database found — skipping seed")
    }
}
