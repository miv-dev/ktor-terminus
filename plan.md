# Claude Code Prompt: FormGraph v2
## Backend-Driven Form Engine with ASP Rules + Bidirectional WebSocket

---

## Core Idea

> "Form logic is data. Data lives in TerminusDB. Logic is evaluated by a rules engine (ASP/Clingo). Results are signals. Signals travel over WebSocket. Frontend only renders."

The frontend sends input events. The backend evaluates rules against current state. Signals flow back. No logic on the frontend — ever.

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Backend server | Ktor (Kotlin), Netty engine |
| Database | TerminusDB (Docker) — graph + Git versioning |
| Rules engine | Clingo (Answer Set Programming, subprocess via ProcessBuilder) |
| Async | kotlinx.coroutines — Channel, SharedFlow, structured concurrency |
| Serialization | kotlinx.serialization |
| HTTP client | Ktor HttpClient (CIO) — for TerminusDB REST API calls |
| Build | Gradle Kotlin DSL |
| Frontend demo | Single `index.html`, vanilla JS, no frameworks |
| Infrastructure | `docker-compose.yml` — TerminusDB + app |

**No third-party TerminusDB clients. No ASP.NET. Clingo is called as a subprocess.**

---

## Domain Model

```kotlin
// Field types
enum class FieldType { TEXT, EMAIL, NUMBER, SELECT, CHECKBOX, PHONE }

// The form schema — stored and versioned in TerminusDB
@Serializable
data class FormSchema(
    val id: String,
    val name: String,
    val fields: List<FormField>
)

@Serializable
data class FormField(
    val id: String,
    val label: String,
    val type: FieldType,
    val placeholder: String = "",
    val options: List<String> = emptyList() // for SELECT
)

// Rules are stored as .lp text in TerminusDB, versioned alongside schema
@Serializable
data class RuleSet(
    val formId: String,
    val lpContent: String  // raw Clingo .lp text
)

// ─── WebSocket Protocol ───────────────────────────────────────

// Frontend → Backend
@Serializable
sealed class InputEvent {
    @Serializable
    data class FieldChanged(val fieldId: String, val value: String) : InputEvent()

    @Serializable
    data class FieldBlurred(val fieldId: String) : InputEvent()  // trigger full validation

    @Serializable
    data class FormSubmitAttempted(val sessionId: String) : InputEvent()

    @Serializable
    data class SessionStarted(val role: String = "user") : InputEvent()
}

// Backend → Frontend
@Serializable
data class FieldSignal(
    val fieldId: String,
    val visible: Boolean,
    val required: Boolean,
    val readOnly: Boolean = false,
    val valid: Boolean? = null,           // null = not yet validated
    val validationMessage: String? = null,
    val hints: List<String> = emptyList() // e.g. "gmail_specific", "yandex_specific"
)

// Full form state snapshot (sent on session start or checkout)
@Serializable
data class FormStateSnapshot(
    val formId: String,
    val commitId: String,
    val signals: List<FieldSignal>
)
```

---

## Architecture

```
TerminusDB
  ├── FormSchema/{id}     ← field definitions, versioned
  └── RuleSet/{id}        ← .lp rules text, versioned alongside schema

         ↓ loaded once per session (or on checkout)

  AspRulesEngine
    - holds cached RuleSet .lp text
    - receives: facts (schema + current values + role)
    - calls: Clingo subprocess (stdin/stdout)
    - returns: AnswerSet → List<FieldSignal>

         ↑ facts injected per event
         ↓ signals

  FormSession (one per WebSocket connection)
  ┌────────────────────────────────────────────┐
  │  sessionId, formId, role                   │
  │  currentValues: ConcurrentHashMap          │
  │  inputChannel: Channel<InputEvent>(cap=32) │
  │  processorCoroutine (launched on connect)  │
  └────────────────────────────────────────────┘
         ↓ List<FieldSignal>

  SignalDispatcher
    - MutableSharedFlow<FieldSignal> per formId
    - broadcast to all subscribers of that form

         ↓ WebSocket frames

  Frontend (index.html)
    - sends InputEvent as JSON
    - receives FieldSignal as JSON
    - only does: show/hide fields, set required/readonly, show validation msg, show hints
```

---

## ASP Rules Engine

### How it works

Clingo receives facts via stdin and outputs an answer set via stdout. The rules file is loaded from the `RuleSet` stored in TerminusDB.

```kotlin
class AspRulesEngine(private val clingoPath: String = "clingo") {

    // Evaluate current form state, returns signals for ALL fields
    suspend fun evaluate(
        schema: FormSchema,
        rules: RuleSet,
        currentValues: Map<String, String>,
        role: String = "user",
        changedField: String? = null  // hint for partial re-eval (future optimization)
    ): List<FieldSignal> = withContext(Dispatchers.IO) {

        val facts = buildFacts(schema, currentValues, role)
        val answerSet = runClingo(facts, rules.lpContent)
        mapToSignals(schema, answerSet)
    }

    private fun buildFacts(
        schema: FormSchema,
        values: Map<String, String>,
        role: String
    ): String = buildString {
        // Declare all fields
        schema.fields.forEach { f ->
            appendLine("field(${f.id}).")
            appendLine("field_type(${f.id}, ${f.type.name.lowercase()}).")
        }
        // Current values
        values.forEach { (k, v) ->
            val escaped = v.replace("\"", "\\\"")
            appendLine("""field_value($k, "$escaped").""")
        }
        // User role
        appendLine("""user_role("$role").""")
    }

    private suspend fun runClingo(facts: String, rulesLp: String): AnswerSet =
        withContext(Dispatchers.IO) {
            val combined = facts + "\n" + rulesLp
            val process = ProcessBuilder(clingoPath, "--outf=2", "-")  // JSON output mode
                .redirectErrorStream(false)
                .start()

            process.outputStream.bufferedWriter().use { it.write(combined) }

            val stdout = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            // Clingo exit codes: 10 = SAT (answer found), 20 = UNSAT, 30 = UNKNOWN
            if (exitCode !in listOf(10, 20, 30)) {
                throw RulesEngineException("Clingo failed with exit code $exitCode")
            }

            parseClinoJson(stdout)
        }
}
```

### Example .lp RuleSet for the demo form

This is the `lpContent` stored in TerminusDB as `RuleSet/client_form`:

```prolog
% ─── Visibility ─────────────────────────────────────────────

visible(client_type).
visible(full_name).
visible(email).
visible(phone).

visible(inn)      :- field_value(client_type, "юрлицо").
visible(kpp)      :- field_value(client_type, "юрлицо").
visible(passport) :- field_value(client_type, "физлицо").

hidden(F) :- field(F), not visible(F).

% ─── Required ────────────────────────────────────────────────

required(client_type).
required(full_name).
required(phone).
required(F) :- visible(F), F = inn.
required(F) :- visible(F), F = kpp.
required(F) :- visible(F), F = passport.

% email required only for физлицо
required(email) :- field_value(client_type, "физлицо").

% ─── Read-only ───────────────────────────────────────────────

readonly(inn)  :- user_role("viewer").
readonly(kpp)  :- user_role("viewer").
readonly(F)    :- user_role("viewer"), field(F).

% ─── Validation ──────────────────────────────────────────────

valid(inn) :- field_value(inn, V), #count{ C : string_code(_,V,C) } = 10.
valid(inn) :- field_value(inn, V), #count{ C : string_code(_,V,C) } = 12.
invalid(inn, "ИНН: 10 цифр для ЮЛ, 12 для ФЛ") :-
    visible(inn), field_value(inn, _), not valid(inn).

valid(kpp) :- field_value(kpp, V), #count{ C : string_code(_,V,C) } = 9.
invalid(kpp, "КПП должен быть 9 символов") :-
    visible(kpp), field_value(kpp, _), not valid(kpp).

% ─── Hints (email domain specifics) ─────────────────────────

hint(email, "gmail_specific")   :- field_value(email, V), string_concat(_, "@gmail.com", V).
hint(email, "yandex_specific")  :- field_value(email, V), string_concat(_, "@yandex.ru", V).
hint(email, "mailru_specific")  :- field_value(email, V), string_concat(_, "@mail.ru", V).
hint(email, "corp_email")       :-
    field_value(email, V),
    not hint(email, "gmail_specific"),
    not hint(email, "yandex_specific"),
    not hint(email, "mailru_specific"),
    string_concat(_, "@", V).  % has @, but not public provider

% ─── Unlock next section ─────────────────────────────────────

% Phone field becomes required only after email is validated
required(phone) :- valid(email).

% Submit allowed only when all visible required fields are valid
submit_allowed :-
    not invalid_required_exists.
invalid_required_exists :-
    required(F), visible(F), invalid(F, _).
invalid_required_exists :-
    required(F), visible(F), not field_value(F, _).
```

### Answer Set → FieldSignal mapping

```kotlin
private fun mapToSignals(schema: FormSchema, answerSet: AnswerSet): List<FieldSignal> =
    schema.fields.map { field ->
        val id = field.id
        FieldSignal(
            fieldId = id,
            visible = answerSet.contains("visible($id)"),
            required = answerSet.contains("required($id)"),
            readOnly = answerSet.contains("readonly($id)"),
            valid = when {
                answerSet.contains("valid($id)") -> true
                answerSet.any { it.startsWith("invalid($id,") } -> false
                else -> null
            },
            validationMessage = answerSet
                .firstOrNull { it.startsWith("invalid($id,") }
                ?.let { extractMessage(it) },
            hints = answerSet
                .filter { it.startsWith("hint($id,") }
                .map { extractHintKey(it) }
        )
    }
```

---

## FormSession — Bidirectional Async Design

```kotlin
class FormSession(
    val sessionId: String,
    val formId: String,
    private val schema: FormSchema,
    private val rules: RuleSet,
    private val engine: AspRulesEngine,
    private val dispatcher: SignalDispatcher
) {
    val currentValues = ConcurrentHashMap<String, String>()
    var role: String = "user"

    // Buffered channel — frontend can type fast, we process sequentially
    private val inputChannel = Channel<InputEvent>(capacity = Channel.BUFFERED)

    // Debounce: coalesce rapid FieldChanged events (e.g. fast typing)
    // Wait 80ms after last event before evaluating
    fun startProcessor(scope: CoroutineScope): Job = scope.launch {
        var pendingEval = false
        var lastChangedField: String? = null

        for (event in inputChannel) {
            when (event) {
                is InputEvent.FieldChanged -> {
                    currentValues[event.fieldId] = event.value
                    lastChangedField = event.fieldId
                    pendingEval = true

                    // Drain any pending events in channel first (debounce)
                    while (inputChannel.tryReceive().isSuccess.also { hasMore ->
                        if (hasMore) {
                            val next = inputChannel.tryReceive().getOrNull()
                            if (next is InputEvent.FieldChanged) {
                                currentValues[next.fieldId] = next.value
                                lastChangedField = next.fieldId
                            }
                        }
                    }) { /* drain */ }

                    if (pendingEval) {
                        evaluate()
                        pendingEval = false
                    }
                }

                is InputEvent.FieldBlurred -> {
                    // Full validation on blur (show errors)
                    evaluate(forceValidation = true)
                }

                is InputEvent.SessionStarted -> {
                    role = event.role
                    evaluate() // send initial state snapshot
                }

                is InputEvent.FormSubmitAttempted -> {
                    evaluate(forceValidation = true)
                    // Additionally emit submit_allowed signal
                }
            }
        }
    }

    private suspend fun evaluate(forceValidation: Boolean = false) {
        val signals = engine.evaluate(
            schema = schema,
            rules = rules,
            currentValues = currentValues.toMap(),
            role = role
        )
        signals.forEach { dispatcher.emit(formId, it) }
    }

    suspend fun onInput(event: InputEvent) = inputChannel.send(event)
    fun close() = inputChannel.close()
}
```

---

## WebSocket Route

```kotlin
webSocket("/ws/form/{formId}") {
    val formId = call.parameters["formId"]!!
    val sessionId = generateSessionId()

    val schema = formRepository.getSchema(formId)
        ?: return@webSocket close(CloseReason(CloseReason.Codes.NORMAL, "Form not found"))
    val rules = formRepository.getRuleSet(formId)
        ?: return@webSocket close(CloseReason(CloseReason.Codes.NORMAL, "Rules not found"))

    val session = FormSession(sessionId, formId, schema, rules, rulesEngine, signalDispatcher)
    sessionRegistry.register(sessionId, session)

    val processorJob = session.startProcessor(this)

    // OUTGOING: signals → frontend
    val outJob = launch {
        signalDispatcher.flowFor(formId).collect { signal ->
            sendSerialized(signal)
        }
    }

    // INCOMING: frontend → session
    val inJob = launch {
        for (frame in incoming) {
            if (frame is Frame.Text) {
                runCatching {
                    val event = Json.decodeFromString<InputEvent>(frame.readText())
                    session.onInput(event)
                }.onFailure {
                    sendSerialized(mapOf("error" to "Invalid event format"))
                }
            }
        }
    }

    // Send initial state immediately
    session.onInput(InputEvent.SessionStarted(role = "user"))

    // Wait for disconnect
    inJob.join()
    outJob.cancel()
    processorJob.cancel()
    session.close()
    sessionRegistry.unregister(sessionId)
}
```

---

## TerminusDB Integration

Use Ktor `HttpClient` only. No third-party TerminusDB clients.

### TerminusClient.kt

```kotlin
class TerminusClient(
    private val http: HttpClient,
    private val base: String,
    private val team: String,
    private val db: String,
    private val user: String,
    private val password: String
) {
    private fun auth(builder: HttpRequestBuilder) =
        builder.basicAuth(user, password)

    suspend fun ensureDb() {
        val exists = runCatching {
            http.get("$base/api/db/$team/$db") { auth(this) }
        }.isSuccess
        if (!exists) {
            http.post("$base/api/db/$team/$db") {
                auth(this)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("label", db)
                    put("comment", "FormGraph database")
                    put("schema", true)
                })
            }
        }
    }

    suspend fun insertDocuments(docs: List<JsonObject>, message: String = "insert") {
        http.post("$base/api/document/$team/$db") {
            auth(this)
            parameter("author", "formgraph")
            parameter("message", message)
            contentType(ContentType.Application.Json)
            setBody(docs)
        }
    }

    suspend fun replaceDocuments(docs: List<JsonObject>, message: String = "update") {
        http.put("$base/api/document/$team/$db") {
            auth(this)
            parameter("author", "formgraph")
            parameter("message", message)
            contentType(ContentType.Application.Json)
            setBody(docs)
        }
    }

    suspend fun getDocument(id: String): JsonObject =
        http.get("$base/api/document/$team/$db") {
            auth(this)
            parameter("id", id)
        }.body()

    suspend fun woqlQuery(query: JsonObject): JsonObject =
        http.post("$base/api/woql/$team/$db") {
            auth(this)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("query", query) })
        }.body()

    suspend fun getCommitLog(): JsonArray =
        http.get("$base/api/log/$team/$db") {
            auth(this)
        }.body()

    suspend fun diff(beforeCommit: String, afterCommit: String): JsonObject =
        http.post("$base/api/diff") {
            auth(this)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("before_data_version", "branch:$team/$db/local/commit/$beforeCommit")
                put("after_data_version", "branch:$team/$db/local/commit/$afterCommit")
            })
        }.body()

    suspend fun checkoutCommit(commitId: String) {
        http.post("$base/api/reset/$team/$db") {
            auth(this)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("commit", commitId)
            })
        }
    }
}
```

---

## REST API Routes

```
POST   /api/forms                              → create schema + ruleset (v1 commit)
GET    /api/forms/{id}                         → current schema
PUT    /api/forms/{id}                         → update schema (new commit)
GET    /api/forms/{id}/rules                   → current .lp ruleset text
PUT    /api/forms/{id}/rules                   → update ruleset (new commit)
GET    /api/forms/{id}/history                 → list commits
GET    /api/forms/{id}/diff?from={c1}&to={c2}  → diff two commits (schema + rules)
POST   /api/forms/{id}/checkout/{commitId}     → rollback + broadcast reset
POST   /api/forms/{id}/evaluate                → body: {role, fieldValues} → List<FieldSignal> (stateless, for testing)

WS     /ws/form/{formId}                       → bidirectional session
```

---

## File Structure

```
formgraph/
├── build.gradle.kts
├── settings.gradle.kts
├── docker-compose.yml
├── Dockerfile
│
├── src/main/kotlin/formgraph/
│   ├── Application.kt
│   │
│   ├── plugins/
│   │   ├── Routing.kt
│   │   ├── WebSockets.kt
│   │   └── Serialization.kt
│   │
│   ├── terminus/
│   │   ├── TerminusClient.kt
│   │   ├── SchemaMapper.kt        ← FormSchema/RuleSet ↔ TerminusDB JSON-LD
│   │   └── TerminusInit.kt        ← ensureDb, pushOWLSchema on startup
│   │
│   ├── asp/
│   │   ├── AspRulesEngine.kt      ← Clingo subprocess, fact builder, answer set parser
│   │   └── AnswerSet.kt           ← data class + parser for Clingo JSON output
│   │
│   ├── domain/
│   │   ├── FormSchema.kt
│   │   ├── RuleSet.kt
│   │   ├── InputEvent.kt
│   │   ├── FieldSignal.kt
│   │   └── EvaluationContext.kt
│   │
│   ├── forms/
│   │   ├── FormRepository.kt      ← CRUD + versioning via TerminusClient
│   │   └── SeedData.kt            ← demo form + rules, seeded on first startup
│   │
│   └── session/
│       ├── FormSession.kt         ← per-connection state + input processor coroutine
│       ├── SessionRegistry.kt     ← ConcurrentHashMap<sessionId, FormSession>
│       └── SignalDispatcher.kt    ← MutableSharedFlow<FieldSignal> per formId
│
└── src/main/resources/
    ├── application.conf
    └── static/
        └── index.html
```

---

## Seed Data (SeedData.kt)

Seed on first startup (check via `GET /api/document` for `FormSchema/client_form`).

### Form: "Заявка клиента"

| Field ID | Label | Type | Options |
|----------|-------|------|---------|
| `client_type` | Тип клиента | SELECT | физлицо, юрлицо |
| `full_name` | ФИО | TEXT | — |
| `email` | Email | EMAIL | — |
| `phone` | Телефон | PHONE | — |
| `inn` | ИНН | TEXT | — |
| `kpp` | КПП | TEXT | — |
| `passport` | Серия и номер паспорта | TEXT | — |

This is **commit v1**. Then apply **commit v2**: add field `comment` (TEXT, always visible, not required), and update `lpContent` to make `phone` always required (not conditional). The `diff` endpoint must reflect both schema and rules changes.

---

## Frontend Demo (index.html)

Single file, vanilla JS, dark industrial theme — dev tool aesthetic, not a polished form.

**Must demonstrate:**
1. Connect to `WS /ws/form/client_form`
2. Send `SessionStarted` on open → receive initial `FormStateSnapshot`
3. Render all fields. Hidden fields have `display:none`
4. On every `<input>` change → send `FieldChanged` event immediately
5. On `<input>` blur → send `FieldBlurred`
6. On signal received → update field: visible/hidden, required marker, validation message, hint badge
7. Sidebar panel:
    - Commit history list (`GET /api/forms/client_form/history`)
    - Click a commit → diff view (before/after fields)
    - "Checkout" button → rollback + watch all fields reset via WS
8. Role switcher: `user` / `viewer` → sends new `SessionStarted` with new role, readonly fields appear

---

## Configuration (application.conf)

```hocon
ktor {
  deployment {
    port = 8080
    watch = [ classes ]
  }
  application {
    modules = [ formgraph.ApplicationKt.module ]
  }
}

terminus {
  url      = "http://terminusdb:6363"
  team     = "admin"
  db       = "formgraph"
  user     = "admin"
  password = "root"
}

asp {
  clingo_path = "clingo"   # override in Docker with full path
}
```

---

## docker-compose.yml

```yaml
version: "3.9"
services:
  terminusdb:
    image: terminusdb/terminusdb-server:latest
    ports:
      - "6363:6363"
    environment:
      TERMINUSDB_ADMIN_PASS: root
    volumes:
      - terminus_data:/app/terminusdb/storage

  formgraph:
    build: .
    ports:
      - "8080:8080"
    depends_on:
      - terminusdb
    environment:
      TERMINUS_URL: http://terminusdb:6363
    volumes:
      - ./src:/app/src  # for hot reload during dev

volumes:
  terminus_data:
```

---

## Dockerfile

```dockerfile
FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y clingo && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## build.gradle.kts

```kotlin
plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("io.ktor.plugin") version "2.3.7"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

application {
    mainClass.set("formgraph.ApplicationKt")
}

val ktorVersion = "2.3.7"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-static-content-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-config-yaml:$ktorVersion")

    // Ktor client (for TerminusDB REST)
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-logging-jvm:$ktorVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Tests
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.22")
}
```

---

## Success Criteria

The prototype is complete when ALL of these pass:

1. `docker-compose up` starts cleanly, DB is seeded, app is ready
2. `GET /api/forms/client_form` returns full schema with all 7 fields
3. `GET /api/forms/client_form/history` returns ≥ 2 commits (v1 seed + v2 migration)
4. `GET /api/forms/client_form/diff?from=v1&to=v2` returns field-level + rules-level delta
5. `POST /api/forms/client_form/evaluate` with `{role:"user", fieldValues:{client_type:"юрлицо"}}` returns signals: `inn` visible+required, `passport` hidden
6. Open `index.html` → select "юрлицо" → `inn` and `kpp` appear, `passport` hides — **via WebSocket signal, no frontend logic**
7. Type `user@gmail.com` in email → hint badge `gmail_specific` appears
8. Switch role to `viewer` → all fields become readonly via signal
9. Checkout v1 from history panel → `comment` field disappears, all sessions receive reset signals
10. Two browser tabs open to same form → signals broadcast to both simultaneously

---

## Architectural Validation

After all criteria pass, create `ARCHITECTURE_NOTES.md` answering:

- **Latency**: What is the p50/p99 round-trip time for a `FieldChanged` → `FieldSignal` cycle on localhost? Is it acceptable for keystroke-level reactivity (<100ms)?
- **Clingo cold start**: Is Clingo spawned per-evaluation or kept alive per-session? What was the measured difference?
- **Backpressure**: What happens if the frontend sends 50 `FieldChanged` events per second? Does the `Channel(BUFFERED)` hold? Does Clingo keep up?
- **TerminusDB failure**: If TerminusDB goes down mid-session, how does the app degrade? Do WebSocket sessions survive?
- **Rules versioning**: What does the diff look like when only rules change (schema unchanged)? Is the delta readable by a non-developer?
- **Scale seam**: What would need to change to support 200 concurrent form sessions? Where is the bottleneck — Clingo, TerminusDB, SharedFlow, or WebSocket?
- **Replacement seam**: If TerminusDB were replaced with another graph DB, how many files change? What is the interface boundary?