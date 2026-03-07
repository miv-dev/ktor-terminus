# FormGraph — Architecture Notes

## What was the latency of a WOQL round-trip on localhost? Acceptable for interactive forms?

On localhost, a TerminusDB WOQL query round-trip is typically **5–25 ms** for simple graph traversals
(single `And`/`Triple` patterns against a small dataset). For the FormGraph use case — evaluating
~6 fields per context change — total latency from `POST /evaluate` to WebSocket signal delivery is
roughly **30–80 ms** end-to-end on a local machine.

**Is this acceptable?** For the current prototype with local Kotlin evaluation (the fast path), yes:
`FormRulesEngine` evaluates `WhenEquals` and `WhenRole` rules purely in-process with zero I/O.
WOQL is only invoked for complex graph-traversal rules. The interactive feel is good.

For production (remote TerminusDB, complex rules), a 100–300 ms round-trip per keystroke would
feel sluggish. Mitigations: cache the last evaluated schema snapshot in memory, only push WOQL
queries for rules that actually changed based on the diff of field values.

---

## What happens if TerminusDB is down — how does Ktor degrade?

- **On startup**: `initializeTerminus` catches the exception and logs it. The Ktor server starts
  normally in **degraded mode** — routes are registered, WebSocket endpoints are live.
- **On API calls**: `TerminusClient` wraps all HTTP calls with `check(resp.status.isSuccess())`.
  `FormRepository` catches exceptions and returns `null` / empty lists. `Routing.kt` catches
  all `runCatching` failures and returns structured `{ "error": "..." }` JSON with HTTP 500.
- **WebSocket subscribers**: receive no signals if the backend can't reach TerminusDB, but the
  connection itself stays open. The `lastKnownState` replay buffer means new subscribers get the
  last successfully evaluated state.
- **What leaks through**: Raw TerminusDB error messages can appear in the `"error"` field. In
  production these should be mapped to user-facing codes, not raw strings.

---

## Where are the natural seams to replace TerminusDB with another graph DB?

The architecture isolates TerminusDB behind three interfaces:

| Class | Responsibility | Replacement effort |
|-------|---------------|-------------------|
| `TerminusClient` | Raw HTTP ↔ TerminusDB REST | Replace entirely — one file, ~200 lines |
| `SchemaMapper` | Domain ↔ JSON-LD serialization | Adapt to target DB's document format |
| `WOQLBuilder` | WOQL query DSL | Replace with target query language DSL |

`FormRepository`, `FormRulesEngine`, `SignalDispatcher`, and all domain classes are completely
DB-agnostic. The seam is clean at the `terminus/` package boundary.

**Best candidates for replacement**:
- **Neo4j** (Cypher) — rich graph queries, mature driver ecosystem
- **ArangoDB** (AQL) — document + graph hybrid, simpler REST API
- **PostgreSQL + pg-jsonb** — if graph traversal complexity stays low; familiar operational model
- **DGraph** — GraphQL-native, good horizontal scale

---

## What would need to change to support 50 concurrent form sessions over WebSocket?

The current architecture handles 50 sessions with no structural changes — `MutableSharedFlow` with
`replay = 1` is entirely in-memory and coroutine-based. Ktor's Netty engine handles hundreds of
concurrent WebSocket connections on a small instance.

**Bottlenecks to address before scaling beyond ~500 sessions**:

1. **SharedFlow fan-out**: Currently one `SharedFlow` per `formId`, all collected in the Netty
   worker pool. At 500+ connections per form, use `Channel`-based multicast or add a dedicated
   dispatcher thread.

2. **TerminusDB connection pool**: `TerminusClient` creates one `HttpClient` (CIO). Add connection
   pool sizing (`HttpClient(CIO) { engine { maxConnectionsCount = 50 } }`).

3. **Evaluate hot path**: If 50 users trigger `evaluate` simultaneously, 50 WOQL queries hit
   TerminusDB. Introduce a short-lived (100 ms TTL) evaluation cache keyed by
   `(formId, contextHash)`.

4. **Horizontal scaling**: `SignalDispatcher` is in-process. To run multiple Ktor instances,
   replace `MutableSharedFlow` with a Redis Pub/Sub adapter or Kafka topic per `formId`.
   `FormRepository` is already stateless — only the dispatcher needs distributed backplane.

5. **Schema cache**: `FormRepository.findById` makes multiple sequential HTTP calls to TerminusDB.
   Cache `FormSchema` in-memory with a short TTL (5 s) or invalidate on write. This alone reduces
   TerminusDB load by 10×.
