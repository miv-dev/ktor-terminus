# FormGraph — CLAUDE.md

## Project

**FormGraph** — backend-driven form schema engine. All form logic (visibility, validation) lives on the backend. Frontend is a dumb WebSocket renderer. TerminusDB is the versioned graph store.

## Stack

- Kotlin 2.3.0 + Ktor 3.4.0 (Netty), JVM 21
- TerminusDB (Docker) — graph DB with Git-style versioning
- kotlinx.serialization for JSON
- Ktor CIO HttpClient for TerminusDB REST calls
- `MutableSharedFlow` for WebSocket fan-out
- Vanilla JS frontend (`index.html`) — no frameworks

## Build & Run

```bash
# Start TerminusDB first
docker compose up terminusdb -d

# Run app (auto-seeds on first start)
./gradlew run

# Compile check
./gradlew compileKotlin

# Build fat JAR for Docker
./gradlew shadowJar
docker compose up --build
```

TerminusDB must be at `http://127.0.0.1:6363` (not `localhost` — JVM resolves to IPv6).

## Package Structure

```
src/main/kotlin/
├── Application.kt          — startup, TerminusDB init, seed v1→v2
├── domain/
│   ├── FormSchema.kt       — FormSchema, FormField, VisibilityRule, ValidationRule (sealed)
│   ├── FieldSignal.kt      — WebSocket signal payload
│   └── EvaluationContext.kt — role + fieldValues for rule evaluation
├── terminus/
│   ├── TerminusClient.kt   — raw Ktor HTTP client for TerminusDB REST API
│   ├── WOQLBuilder.kt      — Kotlin DSL producing WOQL JSON objects
│   └── SchemaMapper.kt     — domain ↔ JSON-LD + OWL schema definition
├── forms/
│   ├── FormRepository.kt   — CRUD, history, diff (Kotlin-computed), checkout
│   ├── FormRulesEngine.kt  — local Kotlin rule evaluation + field validation
│   └── SeedData.kt         — v1 (6 fields) and v2 (phone added, email conditional)
├── signals/
│   └── SignalDispatcher.kt — SharedFlow per formId, replay=1 for new subscribers
└── plugins/
    ├── Routing.kt          — all REST + WebSocket routes
    ├── Serialization.kt    — kotlinx.serialization JSON content negotiation
    └── WebSockets.kt       — Ktor WebSocket plugin config
src/main/resources/
├── application.yaml        — port 8080, terminus config (supports env vars)
└── static/index.html       — dark dev-tool UI, loads schema dynamically from API
```

## API

```
POST   /api/forms                             create form
GET    /api/forms/{id}                        get current schema
PUT    /api/forms/{id}                        update schema (new commit, upserts fields)
GET    /api/forms/{id}/history                commit log
GET    /api/forms/{id}/diff?from={c1}&to={c2} field-level diff (computed in Kotlin)
POST   /api/forms/{id}/checkout/{commitId}    rollback to commit (writes new HEAD + broadcasts)
POST   /api/forms/{id}/evaluate               body: EvaluationContext → List<FieldSignal> + WS broadcast

WS     /ws/form/{formId}                      all field signals
WS     /ws/form/{formId}/field/{fieldId}      single field signals
```

## Key Behaviours

- **Schema JSON discriminator**: kotlinx.serialization sealed classes use `"type"` (not `"@type"`) in REST API payloads. `"@type"` is only for TerminusDB internal JSON-LD storage.
- **Subdocuments**: `VisibilityRule` and `ValidationRule` subclasses have `@subdocument + @key Random` in the TerminusDB schema — required for inline embedding.
- **Upsert on update**: `replaceDocuments` uses `?create=true` so new fields added via PUT are inserted rather than 404ing.
- **Diff**: computed by reading fields at each commit via `queryDocumentsAtCommit`, not TerminusDB's diff API (which requires a specific format and returned empty results).
- **Checkout**: reads schema+fields at target commit, writes them back as new HEAD commit, then broadcasts reset signals.
- **Evaluate + validate**: if `fieldValues` are present in `EvaluationContext`, `FormRulesEngine` also runs validation and populates `valid`/`validationMessage` in signals. Empty context = visibility-only.
- **DB wipe**: `curl -u admin:root -X DELETE http://127.0.0.1:6363/api/db/admin/formgraph` to force re-seed.

## TerminusDB Notes

- Auth: HTTP Basic `admin:root`
- Schema push: all class definitions in one `POST /api/document/admin/formgraph?graph_type=schema` batch
- Reading at commit: `GET /api/document/admin/formgraph/local/commit/{id}?type=FormField`
- TerminusDB must be healthy before app starts — use `docker compose up terminusdb -d` and wait

## Test Payload

`v3.json` in project root — use to create a 3rd commit:
```bash
curl -X PUT http://localhost:8080/api/forms/client_form \
  -H "Content-Type: application/json" -d @v3.json
```
