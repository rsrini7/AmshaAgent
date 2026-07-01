# Self-Evolve Module (`self-evolve`)

This module implements **Phase 8: Self-Evolution Trace Capture** for the Amsha Agent multi-agent framework.

It captures detailed interaction traces (messages, tool calls, tool results, errors, compactions, and resolutions) into a structured persistent audit trail. These traces form the dataset for downstream rule extraction (Phase 9) and offline behavioral directive synthesis (Phase 10), modeled after `hermes-agent-self-evolution`.

---

## 1. Architecture

The module utilizes an asynchronous, event-driven pattern decoupled from the main agent execution path:

```
Core Agent Loop (ToolInvoker / CompactionService)
       │
       ├─ (Fires CDI Event) ─► ToolExecutionEvent / CompactionEvent
       ▼
EvolutionEventObserver (ObservesAsync)
       │
       ▼
DefaultSelfEvolveService.append(event)
       │
       ├─► InMemoryConversationBuffer (per-session lock-free buffer)
       ▼
Flush Trigger (Session End / Threshold / Idle Timeout)
       │
       ▼
EvolutionEventStorePort (Hexagonal Store Port)
       ├─► SqliteEvolutionEventStore (SQLite backend, JDBI)
       └─► PostgresEvolutionEventStore (PostgreSQL backend, JDBI)
```

---

## 2. Configuration Properties

All properties are prefixed with `amsha.self-evolve`.

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `enabled` | `AMSHA_SELF_EVOLVE_ENABLED` | `false` | Enable trace capture (restarts required to bind observers) |
| `backend` | `AMSHA_SELF_EVOLVE_BACKEND` | `sqlite` | Persistence backend to use (`sqlite`, `postgres`, `fs`) |
| `buffer-size` | `AMSHA_SELF_EVOLVE_BUFFER_SIZE` | `50` | Maximum memory buffer size per session |
| `default-search-limit` | `AMSHA_SELF_EVOLVE_DEFAULT_SEARCH_LIMIT` | `8` | Default trace search results limit |
| `directives-enabled` | `AMSHA_SELF_EVOLVE_DIRECTIVES_ENABLED` | `true` | Inject synthesized directives in system prompts (Phase 10) |
| `synthesis-trigger` | `AMSHA_SELF_EVOLVE_SYNTHESIS_TRIGGER` | `manual` | Trigger policy for synthesis (`manual`, `cron`) |
| `flush.max-events` | `AMSHA_SELF_EVOLVE_FLUSH_MAX_EVENTS` | `12` | Flush event size threshold for buffers |
| `flush.idle-ms` | `AMSHA_SELF_EVOLVE_FLUSH_IDLE_MS` | `300000` | Flush idle threshold in milliseconds (5 mins) |

---

## 3. Database Schema

Traces are stored in the `evolution_events` table (defined in Flyway migrations V1__baseline.sql):

| Column | SQLite Type | PostgreSQL Type | Description |
|---|---|---|---|
| `event_id` | `TEXT PRIMARY KEY` | `TEXT PRIMARY KEY` | UUID uniquely identifying this event |
| `session_id` | `TEXT NOT NULL` | `TEXT NOT NULL` | Session identifier |
| `user_id` | `TEXT NOT NULL` | `TEXT NOT NULL` | User identifier |
| `event_type` | `TEXT NOT NULL` | `TEXT NOT NULL` | Type of event (e.g. `TOOL_CALL`, `COMPACTION`) |
| `role` | `TEXT` | `TEXT` | Active persona role (e.g. `coordinator`, `coder`) |
| `content` | `TEXT` | `TEXT` | Message content, arguments, or execution outcome |
| `tool_name` | `TEXT` | `TEXT` | Associated tool name |
| `turn_number` | `INTEGER` | `INTEGER` | Monotonic turn counter |
| `metadata_json` | `TEXT` | `TEXT` | Extensible JSON metadata |
| `created_at` | `TEXT NOT NULL` | `TIMESTAMP WITH TIME ZONE NOT NULL` | Event creation timestamp |

---

## 4. GraalVM Native Image Support

CDI beans and JDBI DAO proxies are explicitly registered for reflection in [SelfEvolveReflectionConfig.java](./src/main/java/com/amsha/selfevolve/runtime/SelfEvolveReflectionConfig.java):
*   `EvolutionEvent`
*   `EvolutionEventType`
*   `FlushReason`
*   `EvolutionEventJdbiDao`
*   `PostgresEvolutionEventJdbiDao`
