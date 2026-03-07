# Claude Code Prompt: FormGraph — Backend-Driven Form Engine

## Project Overview

Build a working prototype called **FormGraph** — a backend-driven form schema management system that demonstrates the following architectural paradigm:

> "All form logic lives on the backend. The frontend is a dumb renderer. WebSockets are the signal pipe. TerminusDB is the versioned graph brain."

This is a proof-of-concept to validate the architecture before scaling it to production. Focus on **correctness and clarity** over optimization.

---

## Tech Stack

- **Backend**: Ktor (Kotlin) — HTTP server + WebSocket server
- **Database**: TerminusDB — graph DB with Git-style versioning (run via Docker)
- **Serialization**: kotlinx.serialization
- **HTTP Client**: Ktor HttpClient (for calling TerminusDB REST API)
- **Build**: Gradle (Kotlin DSL)
- **Infrastructure**: `docker-compose.yml` with TerminusDB service
- **Frontend demo**: Single `index.html` (vanilla JS + WebSocket) — minimal, just enough to prove signals work

---

## Architecture

```
TerminusDB (graph + versioning)
     ↑↓ REST JSON-LD
Ktor Backend
  ├── TerminusClient       — raw HTTP calls to TerminusDB API
  ├── WOQLBuilder          — Kotlin DSL for constructing WOQL queries (Datalog)
  ├── SchemaMapper         — marshal/unmarshal FormSchema ↔ TerminusDB JSON-LD
  ├── FormRepository       — CRUD + versioning operations
  ├── FormRulesEngine      — evaluate visibility/validation rules via WOQL
  ├── SignalDispatcher     — WebSocket broadcast to field-level channels
  └── REST API + WS routes
     ↓ WebSocket signals
Frontend (index.html)
  — subscribes per-field, renders state reactively
```

---

## Domain Model

### Core data classes (kotlinx.serialization)

```kotlin
data class FormSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val fields: List<FormField>
)

data class FormField(
    val id: String,
    val label: String,
    val type: FieldType,      // TEXT, EMAIL, NUMBER, SELECT, CHECKBOX
    val placeholder: String = "",
    val validation: List<ValidationRule> = emptyList(),
    val visibility: VisibilityRule = VisibilityRule.Always
)

sealed class VisibilityRule {
    object Always : VisibilityRule()
    data class WhenEquals(val fieldId: String, val value: String) : VisibilityRule()
    data class WhenRole(val role: String) : VisibilityRule()
    data class WhenAny(val rules: List<VisibilityRule>) : VisibilityRule()
}

sealed class ValidationRule {
    object Required : ValidationRule()
    data class MinLength(val min: Int) : ValidationRule()
    data class MaxLength(val max: Int) : ValidationRule()
    data class MatchesRegex(val pattern: String) : ValidationRule()
    data class RequiredWhen(val condition: VisibilityRule) : ValidationRule()
}

// Signal sent over WebSocket to frontend
data class FieldSignal(
    val fieldId: String,
    val visible: Boolean,
    val required: Boolean,
    val valid: Boolean? = null,
    val validationMessage: String? = null,
    val reason: String? = null
)

// Context for rule evaluation
data class EvaluationContext(
    val role: String = "user",
    val fieldValues: Map<String, String> = emptyMap()
)
```

---

## TerminusDB Integration

TerminusDB exposes a REST API. Do **not** use any third-party Kotlin/Java client — use Ktor's own `HttpClient` with `ContentNegotiation` + JSON.

### Key TerminusDB endpoints to use:

| Operation | Endpoint |
|-----------|----------|
| Create DB | `POST /api/db/{team}/{db}` |
| Insert documents | `POST /api/document/{team}/{db}` |
| Get document | `GET /api/document/{team}/{db}?id={id}` |
| Replace document | `PUT /api/document/{team}/{db}` |
| Delete document | `DELETE /api/document/{team}/{db}?id={id}` |
| WOQL query | `POST /api/woql/{team}/{db}` |
| Commit log | `GET /api/log/{team}/{db}` |
| Diff commits | `GET /api/diff` with `before` / `after` body |
| Branch | `POST /api/branch/{team}/{db}/{branch}` |

Auth: HTTP Basic (`admin` / `root` for local dev).

### JSON-LD schema for FormField in TerminusDB:

```json
{
  "@type": "FormField",
  "@id": "FormField/email_field",
  "label": "Email",
  "fieldType": "EMAIL",
  "visibilityRule": {
    "@type": "AlwaysVisible"
  },
  "validationRules": [
    { "@type": "RequiredRule" },
    { "@type": "RegexRule", "pattern": "^[^@]+@[^@]+$" }
  ]
}
```

Define the OWL schema for these types and submit it to TerminusDB on startup (`POST /api/schema/{team}/{db}`).

---

## WOQL Rules Engine

Use WOQL (JSON format, not the JS client) to evaluate rules. Example — find all fields visible for a given context:

```json
{
  "@type": "woql:And",
  "woql:query_list": [
    {
      "@type": "woql:Triple",
      "woql:subject": { "@type": "woql:Variable", "woql:variable_name": "Field" },
      "woql:predicate": { "@value": "rdf:type", "@type": "xsd:anyURI" },
      "woql:object": { "@value": "FormField", "@type": "xsd:anyURI" }
    },
    {
      "@type": "woql:Triple",
      "woql:subject": { "@type": "woql:Variable", "woql:variable_name": "Field" },
      "woql:predicate": { "@value": "scm:form", "@type": "xsd:anyURI" },
      "woql:object": { "@value": "FormSchema/client_form", "@type": "xsd:anyURI" }
    }
  ]
}
```

Build a `WOQLBuilder` Kotlin class that constructs these JSON objects programmatically via a small DSL. Do not hardcode JSON strings.

---

## API Routes

### REST

```
POST   /api/forms                          → create form schema (inserts to TerminusDB, returns schema id + commit id)
GET    /api/forms/{id}                     → get current schema
PUT    /api/forms/{id}                     → update schema (new commit)
GET    /api/forms/{id}/history             → list commits from TerminusDB log
GET    /api/forms/{id}/diff?from={c1}&to={c2} → diff two commits (field-level changes)
POST   /api/forms/{id}/checkout/{commitId} → restore schema to commit (then broadcast reset signals)
POST   /api/forms/{id}/evaluate            → body: EvaluationContext → returns List<FieldSignal>
```

### WebSocket

```
WS /ws/form/{formId}                → broadcast all field signals for this form
WS /ws/form/{formId}/field/{fieldId} → signals for a single field only
```

Signal dispatcher: use `kotlinx.coroutines` `MutableSharedFlow` per `formId`. On new WebSocket connection, immediately emit current state for all fields.

---

## Demo Scenario (Hardcoded Seed Data)

On first startup, seed TerminusDB with this form schema:

**"Заявка клиента" (Client Application Form)**

| Field ID | Label | Type | Visibility | Validation |
|----------|-------|------|------------|------------|
| `client_type` | Тип клиента | SELECT (физлицо/юрлицо) | Always | Required |
| `full_name` | ФИО | TEXT | Always | Required, MinLength(2) |
| `email` | Email | EMAIL | Always | Required, Regex(email) |
| `inn` | ИНН | TEXT | WhenEquals(client_type, юрлицо) | Required, MinLength(10), MaxLength(12) |
| `kpp` | КПП | TEXT | WhenEquals(client_type, юрлицо) | Required, MinLength(9), MaxLength(9) |
| `passport` | Серия и номер паспорта | TEXT | WhenEquals(client_type, физлицо) | Required, Regex(^\d{4} \d{6}$) |

This schema is **v1**. Then apply a migration to **v2**: add field `phone` (TEXT, Always, Required), and change `email` to not-required for юрлицо. Record both commits. The diff endpoint must show this delta.

---

## Frontend Demo (index.html)

Single file. No frameworks. Purpose: prove the WebSocket signals work.

- Show all form fields
- Connect to `WS /ws/form/client_form`
- When signal arrives: toggle field visibility, show/hide validation state
- Two buttons: "Физлицо" / "Юрлицо" — trigger `POST /api/forms/client_form/evaluate` with the respective context, then the signals flow back over WS
- Show commit history panel: list commits, click to diff, click to checkout
- Style: minimal, industrial, dark theme — like a dev tool, not a pretty form

---

## File Structure

```
formgraph/
├── build.gradle.kts
├── settings.gradle.kts
├── docker-compose.yml           ← TerminusDB + app
├── Dockerfile
├── src/main/kotlin/
│   ├── Application.kt
│   ├── plugins/
│   │   ├── Routing.kt
│   │   ├── WebSockets.kt
│   │   └── Serialization.kt
│   ├── terminus/
│   │   ├── TerminusClient.kt
│   │   ├── WOQLBuilder.kt
│   │   └── SchemaMapper.kt
│   ├── domain/
│   │   ├── FormSchema.kt
│   │   ├── FieldSignal.kt
│   │   └── EvaluationContext.kt
│   ├── forms/
│   │   ├── FormRepository.kt
│   │   ├── FormRulesEngine.kt
│   │   └── SeedData.kt
│   └── signals/
│       └── SignalDispatcher.kt
└── src/main/resources/
    ├── application.conf
    └── static/
        └── index.html
```

---

## Key Implementation Notes

1. **TerminusDB schema registration**: On startup, if DB doesn't exist — create it, push OWL schema, seed data. Use a simple `isInitialized` flag in application.conf or check via API.

2. **WOQL evaluation**: `FormRulesEngine` must evaluate rules locally in Kotlin for speed (for simple `WhenEquals` cases), and fall back to WOQL for complex graph-traversal rules. Both paths must work.

3. **Versioning**: Every `PUT /api/forms/{id}` must result in a new TerminusDB commit. Commit messages should be auto-generated: `"Update form {id}: added {n} fields, modified {m} fields"`.

4. **SignalDispatcher lifecycle**: One `SharedFlow` per form. On checkout — re-evaluate all fields with empty context and broadcast reset signals to all subscribers.

5. **Error handling**: TerminusDB errors (4xx, 5xx) must be caught, logged, and returned as structured JSON errors from Ktor. Never let TerminusDB error responses leak raw to client.

6. **Coroutines**: All TerminusDB calls are `suspend`. Use `Dispatchers.IO` for HTTP calls. WebSocket collectors run in the connection's coroutine scope.

7. **Configuration** (`application.conf`):
```hocon
terminus {
    url = "http://localhost:6363"
    team = "admin"
    db = "formgraph"
    user = "admin"
    password = "root"
}
ktor {
    deployment.port = 8080
}
```

---

## Dependencies (build.gradle.kts)

```kotlin
val ktorVersion = "2.3.7"
val kotlinVersion = "1.9.22"
val logbackVersion = "1.4.14"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-logging-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
}
```

---

## Success Criteria

The prototype is successful when:

1. `docker-compose up` starts everything
2. `GET /api/forms/client_form` returns the seeded schema
3. `GET /api/forms/client_form/history` returns at least 2 commits (seed v1 + migration v2)
4. `GET /api/forms/client_form/diff?from=v1&to=v2` returns field-level delta
5. `POST /api/forms/client_form/checkout/{v1_commit_id}` rolls back to v1
6. Opening `index.html` — clicking "Юрлицо" hides `passport`, shows `inn` and `kpp` via WebSocket signal
7. Clicking "Физлицо" does the reverse
8. All without any logic in `index.html` except WebSocket subscription + DOM update

---

## What to Validate Architecturally

After building, add a short `ARCHITECTURE_NOTES.md` answering:
- What was the latency of a WOQL round-trip on localhost? Acceptable for interactive forms?
- What happens if TerminusDB is down — how does Ktor degrade?
- Where are the natural seams to replace TerminusDB with another graph DB later?
- What would need to change to support 50 concurrent form sessions over WebSocket?