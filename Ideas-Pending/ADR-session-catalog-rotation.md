# ADR: Persistent Session Catalog & Cross-Channel `/newsession` Rotation

- **Status:** Approved
- **Date:** 2026-07-12
- **Supersedes:** Ideas-Pending/BACKLOG.md items "Cross-Channel `/newsession` Session Rotation" (#1) and "Persistent Session Catalog and Resume Picker" (#2)
- **Related:** Done backlog item "Unify Channel Dispatch and Intent Classification" (P106, `TurnDispatchService`)

## 1. Context

Today a user's session identity spans three layers, but only two of them are
visible or queryable:

| Layer | What it is | Persisted? | Today |
|---|---|---|---|
| **Mapping** | `(channelType, channelId, userId)` → `amshaSessionId` | Telegram/WS via `SessionRegistry` table (SQL only); CLI never maps (uses `cli-<uuid>`); FS uses `FallbackSessionIdResolver` deterministic UUID v3 (no table) | Only the **single current** session, and **not** FS-parity |
| **Liveness** | `SessionHeartbeatTracker` (owner, autonomy, tokens) | ❌ in-memory only | Lost on restart |
| **History** | `StatefulAgentStore` + `ChatMemoryStore`, keyed by `amshaSessionId` | ✅ SQLite/PG/FS | Survives restart |

Consequences observed in code:

- `SessionRegistry.resolveOrCreate(...)` (`app/.../session/SessionRegistry.java:71`,
  `@IfBuildProperty(quarkus.flyway.enabled=true)`) stores exactly one row per
  `(channel_type, channel_id, user_id)` and is **SQL-only**. Under the FS backend,
  `FallbackSessionIdResolver` (`core/.../pipeline/FallbackSessionIdResolver.java:30`)
  derives a deterministic UUID v3 from the triplet and there is **no**
  `session_registry` table at all.
- CLI (`app/.../cli/ChatCommand.java:~111`) generates `cli-<UUID>` per process and
  never registers it, so `--session-id` resume is the only recovery path and there
  is no "most recent session" concept.
- `SessionResolutionHandler` (`core/.../pipeline/handlers/SessionResolutionHandler.java:56`)
  resolves the active id from the mapping or a caller-supplied id; it does **not**
  record sessions anywhere durable for later discovery.
- `ResetReflex` (`core/.../reflex/ResetReflex.java:62`) wipes the *current* session
  in place. There is no command that preserves the old session and opens a new one.
- `ChannelCommand.parse()` (`channel-api/.../ChannelCommand.java:74`) has its own
  exhaustive `(switch cmd)` that returns `Unknown` for anything not listed, and
  `CommandDispatchHandler.handle` (`core/.../pipeline/handlers/CommandDispatchHandler.java:55`)
  has an exhaustive `switch (bic)` with no `default`. Both must be extended together
  when new permits are added.

Two backlog items ask for overlapping capability:

1. **`/newsession`** — start a fresh session while keeping the old session's
   persisted history (CLI/Telegram/WS).
2. **Session catalog + resume picker** — discover historical sessions, resume by
   id/number, `--last-session`, `--new-session`.

These are the *same missing layer*: a durable catalog of every session a user has
ever had. Once that exists, item #2 is read-only views over the catalog and item #1
is "insert a new active catalog row + flip the active flag; old row/history intact."

## 2. Decision

Introduce a single durable **`SessionCatalog`** persistence port plus a
transport-neutral **`SessionCatalogService`** in `amsha-core`. The catalog is the
**single source of truth for the active session mapping** (it owns an `active`
flag per scope), which replaces the dual-write to `session_registry` and — critically —
makes rotation work identically on SQLite, PostgreSQL, **and the filesystem backend**.
All channel adapters (CLI, Telegram, WebSocket/`WebChatEndpoint`, REST) and all
commands (`/newsession`, `/sessions`, `/resume`, `--last-session`, `--new-session`)
are implemented as views or operations over it. This reuses the existing unified
dispatch path (`TurnDispatchService`) so CLI/Telegram/WS/REST share identical routing.

### 2.1 Persistence port — `SessionCatalogStore`

`persistence-api/.../SessionCatalogStore.java` (mirror `CompactionStorePort`):

```java
public interface SessionCatalogStore {
    void record(SessionCatalogEntry entry);                 // upsert by sessionId (metadata only)
    void touch(String sessionId, String ownerKey, String channelType,
               String channelId, String role, String goalPreview); // upsert-or-create, then set lastUsedAt
    Optional<SessionCatalogEntry> resolveActive(String ownerKey, String channelType, String channelId); // single active row for scope
    void activateNew(SessionCatalogEntry newActiveEntry);  // atomically deactivates siblings and inserts/activates new
    void setActive(String sessionId, boolean active);        // flips flag; deactivates all siblings in scope
    List<SessionCatalogEntry> listByScope(String ownerKey, String channelType, String channelId);
    List<SessionCatalogEntry> listInactiveBefore(Instant cutoff);
    Optional<SessionCatalogEntry> latestByScope(String ownerKey, String channelType, String channelId);
    Optional<SessionCatalogEntry> bySessionId(String sessionId);
    Optional<SessionCatalogEntry> bySequence(String ownerKey, String channelType, String channelId, int seq);
    void archive(String sessionId);                         // sets status = ARCHIVED, active = false
}
```

**Status Values & Resumability Constraints:**
- `ACTIVE`: The current single active session for the scope. Acceptable for chat turns.
- `INACTIVE`: Historical sessions that can be resumed.
- `ARCHIVED`: Historical sessions that have been archived.
- **Archive Behavior:** Calling `archive(sessionId)` atomically sets `status = ARCHIVED` and `active = false` (releasing any active hold).
- **Resume Protection:** Any attempt to call `resume(...)` on a session with `status = ARCHIVED` MUST be rejected by the service with an `IllegalArgumentException` (archived sessions are permanently read-only and cannot be reactivated).
- **Archive Trigger:** `SessionReaper` runs a daily, non-transactional catalog
  sweep. It archives `INACTIVE` entries with `lastUsedAt` older than
  `amsha.session-catalog.archive-after-days` (default `90`). The port supplies
  `listInactiveBefore(Instant cutoff)`; each `archive()` is a short adapter-owned
  write. Active and awaiting-approval sessions are never candidates.

**`activateNew` Atomic store operation (resolves the insert-before-deactivate race):**
To satisfy the partial unique index, the catalog rotation cannot do a plain insert before deactivating. The store adapter must expose `activateNew(...)` which runs atomically as a single database transaction or filesystem write unit:
- **SQL (SQLite/PostgreSQL):** Runs in a single transaction:
  1. Deactivate current active session in the scope: `UPDATE session_catalog SET active = 0, status = 'INACTIVE' WHERE owner_key = :ok AND channel_type = :ct AND channel_id = :ci AND active = 1`.
  2. Insert the new active catalog entry: `INSERT INTO session_catalog (..., active, status) VALUES (..., 1, 'ACTIVE')`.
- **Filesystem (FsSessionCatalogAdapter):** Synchronized via `FsLockManager` (directory/file lock):
  1. Read all files in the directory `sessions/scopes/{ownerKey}/{channelType}/{channelId}/`.
  2. For any entry with `active = true`, write it back with `active = false` and `status = 'INACTIVE'`.
  3. Write the new active session file (`active = true`, `status = 'ACTIVE'`).

`activateNew(...)` assigns `seq = max(seq in scope) + 1` when the supplied value
is `0`/unset, using the same adapter-owned rule as `record()`. This makes every
new and rotated session addressable by `/resume <number>`.

`SessionCatalogEntry` record fields: `id, ownerKey, channelType, channelId,
sessionId, role, goalPreview (<=140 safe chars, no secrets), createdAt, lastUsedAt,
seq, active (boolean), status`. Indexes:
  - **Partial unique index** enforcing "at most one active session per scope"
    (SQLite): `CREATE UNIQUE INDEX IF NOT EXISTS idx_session_catalog_active ON
    session_catalog(owner_key, channel_type, channel_id) WHERE active = 1;`
    (PostgreSQL): `CREATE UNIQUE INDEX idx_session_catalog_active ON
    session_catalog(owner_key, channel_type, channel_id) WHERE active = TRUE;`
  - **Non-unique query index** `(owner_key, channel_type, channel_id, last_used_at)`
    for `latestByScope`/`listByScope` ordering.
  - **Non-unique query index** `(owner_key, channel_type, channel_id, active)` for
    scope scans.
  NB: a plain `UNIQUE` index on `(owner_key, channel_type, channel_id, active)` would
  fail after the first rotation - multiple inactive siblings share `active = false`.

**Identity & scope terminology (resolves the ownerKey ambiguity):**
- `ownerKey` = the resolved user identity. For REST/Telegram/WS it is the platform
  `userId`; for CLI it is `CLI_USER_ID = "cli-user"`.
- `channelId` = the platform channel/chat id; for CLI it is `CLI_CHAT_ID = "cli-chat"`.
- `channelType` = canonical lowercase string `ChannelType.id()` (e.g. `"cli"`, `"telegram"`, `"websocket"`, `"rest_api"`).
- **Resume ownership check compares the caller's resolved `(ownerKey, channelType,
  channelId)` triple against the catalog row's triple — an exact match, not ownerKey
  alone.**

**`seq` assignment (resolves the unspecified ownership):** the *store adapter*
assigns `seq = (max seq in scope) + 1` on both `record()` and `activateNew()` when
the provided `seq` is `0`/unset. `bySequence` then works uniformly, including the FS append-log adapter
(which sorts rows by insertion order). The service never computes `seq`.

**`touch` semantics for pre-catalog sessions (resolves the undefined behavior):**
`touch(...)` updates an existing row's `lastUsedAt` and, if non-blank,
`role`/`goalPreview`. Creation of a missing legacy row is performed by
`resolveOrCreateSession(...)` through `activateNew(...)`, never by a bare active
`record()`, so a concurrent or partially migrated scope cannot violate the partial
unique index.
`goalPreview` is `null` on a brand-new row and only populated once a goal is seen —
`/sessions` must render an empty preview (e.g. "—") rather than crash.

**Three adapters with parity** (required by AGENTS.md). Migrations live under the
backend-specific subdirectory (actual tree: `V1__baseline.sql`,
`V2__extracted_memories.sql`, `V3__directives.sql` → `V4__` is the correct next):
- `persistence-sqlite/.../SqliteSessionCatalogAdapter.java` + Flyway
  `persistence-sqlite/src/main/resources/db/migration/sqlite/V4__session_catalog.sql`
- `persistence-postgres/.../PostgresSessionCatalogAdapter.java` + Flyway
  `persistence-postgres/src/main/resources/db/migration/postgresql/V4__session_catalog.sql` (identical semantics/columns/index)
- `persistence-fs/.../FsSessionCatalogAdapter.java` (JSON/append log under segmented directory structure: `sessions/by-id/{sessionId}.json` and `sessions/scopes/{ownerKey}/{channelType}/{channelId}/{sessionId}.json`; `seq` derived from scope list size)

### 2.2 Core service — `SessionCatalogService` (`amsha-core`)

- **`resolveOrCreateSession(channelType, channelId, userId)`** — the unified
  resolver used by `SessionResolutionHandler` (replaces the direct call to
  `sessionIdResolver.resolveOrCreate(...)`):
  1. Canonicalize: `String canonicalChannelType = channelType.toLowerCase(Locale.ROOT)`.
  2. `catalog.resolveActive(ownerKey=userId, canonicalChannelType, channelId)` → if present,
     return its `sessionId` (catalog is now the single source of truth).
  3. **Legacy Upgrade Migration Rule:** If absent in the catalog, query the legacy `SessionIdResolver`. If the legacy resolver is the SQL-based `SessionRegistry`, perform a legacy lookup in the `session_registry` table using BOTH the canonical lowercase `canonicalChannelType` and the legacy uppercase `channelType.toUpperCase(Locale.ROOT)`. 
     * If a legacy `sessionId` is found, lazily migrate it through `catalogStore.activateNew(...)` with the canonicalized lowercase `canonicalChannelType`.
     * If no legacy session exists, generate a new UUID, persist it through `catalogStore.activateNew(...)` as `ACTIVE`, and return it.
  This keeps `SessionRegistry`/`FallbackSessionIdResolver` **unchanged** (they remain
  only the pre-catalog fallback) and makes FS rotation work because the active
  mapping now lives in the catalog (which has an FS adapter).
- **`createSession(userId, autonomyLevel, channelType, channelId)`** — generate
  UUID, canonicalize inputs, call `catalogStore.activateNew(new active catalog
  entry)`, and return the id. A session created for an existing scope is therefore
  a deliberate rotation rather than a partial-index violation.
  Called by `AgentSessionService.createSession` (REST) and CLI startup so every new
  session is catalogued from birth.
- **`rotate(channelType, channelId, userId, currentSessionId)`** — the `/newsession`
  engine. It generates a new UUID and performs the rotation in the following strict order:
  1. **Atomic Store Operation (Rollback Boundary):** Call `catalogStore.activateNew(newActiveEntry)` which atomically deactivates siblings and inserts the new active session in a single database transaction or filesystem write lock. If the write fails, abort and throw an exception; no state changes.
  2. **External HITL State (Best Effort):** Call `ApprovalNotificationService.flushPendingNotifications(chatId)` (if applicable) to clear old active approvals. If this fails, log a warning but do not roll back the database rotation.
  3. **In-Memory Cache (Guaranteed):** Re-bind heartbeat ownership and active autonomy to the new session ID via `heartbeatTracker.recordHeartbeat(...)`.
- **`resume(targetId, channelType, channelId, userId)`** — ownership-checked
  (caller's `(ownerKey, channelType, channelId)` triple must equal the target row's). Reject the resume operation if the target session has `status = 'ARCHIVED'` (archived sessions are read-only). Otherwise, call `catalog.setActive(targetId, true)` (deactivates siblings). Returns the id.
- `listScoped(...)`, `latestByScope(...)`, `rotateAndNotify(...)` (publishes
  `SessionRotatedEvent` on `DomainEventBus`; `SessionRotatedEvent` is a plain event
  class. Per the `EventTypeCatalog` contract ("every event publication MUST reference
  a constant from this catalog"), add
  `public static final Class<SessionRotatedEvent> SESSION_ROTATED =
  SessionRotatedEvent.class;` to `EventTypeCatalog` and publish via
  `eventBus.publish(EventTypeCatalog.SESSION_ROTATED, new SessionRotatedEvent(...))`.
  Consumed via `CdiDomainEventBus` (`@Observes SessionRotatedEvent`).

**Deterministic `goalPreview` Redaction Policy:**
To prevent leakage of API keys, passwords, or credentials in public/durable lists,
`SessionResolutionHandler` injects the existing `@InputSanitizer.PII InputSanitizer`
and uses `sanitizer.sanitize(context.getMessage().text())` before passing a preview
to the catalog. The entry's compact constructor then truncates that sanitized value
to 140 characters. This reuses the project's sole PII-redaction policy; no
catalog-specific regex redactor is introduced. The preview source is the inbound
message text because this handler runs at priority 40, before goal resolution.

`SessionResolutionHandler` calls `sessionCatalogService.resolveOrCreateSession(...)`
instead of `sessionIdResolver.resolveOrCreate(...)`, sanitizes the inbound message
text as above, and calls `catalog.touch(sessionId, ownerKey, channelType, channelId, role, preview)` each
turn so `lastUsedAt` (and thus `--last-session` / `/resume`) is meaningful after a
restart/native rebuild.

### 2.3 Channel adapters

- **CLI** (`app/.../cli/ChatCommand.java`): add `--last-session` and `--new-session`
  picocli options. Startup resolution order: explicit `--session-id` →
  `--new-session` (fresh `cli-<uuid>`) → `--last-session`
  (`catalog.latestByScope(cli-user, "cli", "cli-chat")` — note canonical
  lowercase `channelType`) → default new `cli-<uuid>`. Each path calls
  `catalog.createSession`/`record` so the chosen id is catalogued.
  **Session rotation sync:** the `NewSessionReflex` performs the rotation inside the
  pipeline and does **not** touch the CLI's local `currentCliSessionId` field, so
  `ChatCommand` must learn the new id from the dispatch result. After
  `turnDispatchService.dispatch(...)` returns, if `result.sessionId()` differs from
  `currentCliSessionId`, update `currentCliSessionId`, re-record the heartbeat, print
  the switch, and add `/newsession`, `/sessions`, `/resume` to
  `extractSlashCommands()` for tab-completion. (CLI does **not** use `SessionRegistry`;
  rotation is purely a catalog active-flag flip + local variable update.)
  **`WorkspaceContext` rebinding:** the CLI currently binds `WorkspaceContext` once
  for the whole chat loop (`runChatLoop`), so a rotated id will not reach
  `WorkspaceManager` on subsequent turns. Either re-establish `WorkspaceContext` per
  turn (as TG/WS do) or re-read the active id before each `dispatchToWorkflow` so the
  new session id propagates.
- **Telegram / WebSocket**: resolution already goes through
  `SessionResolutionHandler` → `resolveOrCreateSession`, so rotation/resume operate
  purely on the catalog active flag. `SessionRegistry.resolveOrCreate` is **unchanged**
  (still only the pre-catalog fallback). `/newsession` flips the active flag to a new
  id; the old `amshaSessionId` remains in the catalog and `StatefulAgentStore`.
- **WebSocket/UI** (`channel-web/.../WebChatEndpoint.java` +
  `WebSocketSessionStore`): `rotateAndNotify(...)` publishes `SessionRotatedEvent`.
  A new listener in `WebChatEndpoint` replaces the entire `ConnectionState` entry in
  the `connections` map for that `conn.id()` (it is an immutable record, so it cannot
  be mutated in place) with a copy carrying the new `amshaSessionId`, and calls a new
  `WebSocketSessionStore.updateAmshaSession(platformId, newId)` (which updates
  `platformToAmsha` and `connToAmshaSession` — the store currently only has
  `register`/`unregister`/`get`, so this method is added). It then sends a
  `session-rotated` WS message so the UI switches its active ID for REST/HITL calls
  (consistent with `buildConnectedJson` contract, `WebChatEndpoint.java:235`).
- **REST** (`core/.../api/AgentSessionService.java`): `createSession` currently only accepts `userId` and `autonomyLevel`. It must be updated to accept the full scope inputs:
  - `createSession(String userId, AutonomyLevel autonomyLevel, String channelType, String channelId)`
  - Preserve `createSession(String userId, AutonomyLevel autonomyLevel)` for
    `AgentResource`; it delegates to the scoped overload with
    `channelType = "rest_api"` and `channelId = "rest-chat"`. This retains REST
    API compatibility while giving direct callers an explicit scope.
  - **REST Scope Model Mapping:**
    - `ownerKey` = the resolved `userId` (passed via REST payload or resolved from authentication).
    - `channelType` = always canonical lowercase `"rest_api"`.
    - `channelId` = a client-supplied identifier passed in the REST request (e.g. `"web-chat-v1"`, `"slack-connector"`). If the client does not supply a `channelId`, it defaults to `"rest-chat"`.
  - The REST API DTO (`CreateSessionRequest`) must be updated to add `channelId` (optional string) and `userId` (optional string) to allow clients to specify the exact scope.
  - Recording the catalog row inside `createSession` with this scope ensures that REST-created sessions are properly isolated and discoverable under `/sessions` or `/resume`.

### 2.4 Commands & reflexes

`ChannelCommand` (`channel-api/.../ChannelCommand.java`): add permits `NewSession`,
`Sessions`, `Resume(String target)`; extend `CMD_PATTERN` to include
`newsession|sessions|resume`. **Both** the `parse()` switch *and* the
`CommandDispatchHandler.handle` exhaustive `switch (bic)` must be updated
(`CommandDispatchHandler.java` is added to §5 Modify). Dispatch routes
`NewSession`/`Sessions`/`Resume` to their reflexes (grep confirms only one exhaustive
`switch (bic)` exists; no other renderer switches need touching). New reflexes
registered via `@ReflexPriority`, picked up by `ReflexRegistry` like
`ResetReflex`/`HistoryReflex`:

- `NewSessionReflex` → `SessionCatalogService.rotate(...)`. **HITL handling is
  transport-aware (resolves the CLI dead-path nit):**
  - **CLI:** `ChatCommand`'s `hitlPending` guard (`ChatCommand.java:225`, `:266`)
    already blocks `/newsession` from being dispatched while an approval is pending,
    so the CLI branch never reaches a live HITL waiter. The reflex therefore does
    **not** call `cliResultBridge.cancel` on CLI — that path would be dead code.
  - **Telegram/WS:** if a pending approval exists for the current session, the reflex
    calls `ApprovalNotificationService.flushPendingNotifications(chatId)` (per the AGENTS.md
    HITL rule that Telegram approval callbacks bypassing `AbstractDecisionReflex`
    must flush) **before** rotating, so a stale approval is not left attached to the
    old session id. `CliResultBridge` is still an injectable core bean
    (`ResumeResultHelper`/`LoopExitPublisher`) and may be used if a CLI HITL waiter
    is somehow live, but the primary CLI guarantee is the guard.
- `SessionsReflex` → `catalog.listScoped(...)` for the caller (scoped to their
  `(ownerKey, channelType, channelId)` triple), rendered as a numbered list using
  `seq` (enables `/resume <number>`).
- `ResumeReflex` → parse id or number → ownership-checked `catalog.resume(...)`.

All flow through `TurnDispatchService`, preserving the unified routing guarantee.

### 2.5 Isolation guarantees

`rotate()` must, per channel, atomically:
- Cancel/flush the in-flight HITL state for the old session (TG/WS:
  `flushPendingNotifications()`; CLI: the `hitlPending` guard already prevents
  dispatch — see §2.4).
- **Not** copy workspace dir, role context, or autonomy — the new session starts
  coordinator-default with a fresh workspace, since `WorkspaceManager` is keyed by
  `sessionId` and a new uuid yields a clean slate automatically.
- Re-bind heartbeat ownership to the new id; retain the old id's ownership in the catalog.

### 2.6 AGENTS.md compliance (implementation detail, not optional)

- **Persistence:** JDBI query records need `@RegisterConstructorMapper`; the SQLite/
  Postgres adapters inject `JdbiProducer` and use `@BindMethods` for writes. FS
  adapter follows the existing `Fs*Adapter` append/JSON pattern.
- **Native image:** `SessionCatalogEntry` requires reflection registration in
  `core/.../config/CoreReflectionConfig`; any SQLite/Postgres JDBI DTO rows require
  registration in `SqlitePersistenceReflectionConfig` /
  `PostgresPersistenceReflectionConfig`. Mark native-access libraries for run-time
  init by package.
- **Immutability/validation:** static data uses `Map.copyOf()`; `SessionCatalogEntry`
  validates cross-field rules in a compact constructor — **truncate** (do not throw)
  `goalPreview` to <=140 safe chars (users can submit arbitrarily long goals, so a
  throwing constructor would crash the turn during `record`/`touch`), and enforce
  non-null `sessionId`/`ownerKey`/`channelType`/`channelId`.
- **SQLite anti-nesting:** catalog writes are short and must not be nested inside an
  active JTA/transaction (no `REQUIRES_NEW` inside active writes) to avoid
  `SQLITE_BUSY` — per AGENTS.md persistence invariants. `rotate`/`resume` orchestrate
  the catalog + heartbeat writes directly, not inside a wider transaction.
- **Config:** any new `@ConfigProperty` is added to `application.yaml`, `.env.example`,
  and the relevant module README.

## 3. Consequences

**Positive**
- Items #1 and #2 collapse into one durable abstraction with one source of truth.
- **FS backend parity achieved** — the active mapping lives in the catalog (which has
  an FS adapter), so `/newsession`/`/resume` behave identically on SQLite,
  PostgreSQL, and filesystem. This directly fixes the previous SQL-only
  `SessionRegistry.repoint` design.
- Historical sessions become discoverable and resumable across restarts and native
  rebuilds (catalog is on the same backend as checkpoints).
- Strict tenant isolation: `listScoped`/`resume` are scoped by the
  `(ownerKey, channelType, channelId)` triple; a session from another user/chat is
  neither listed nor resumable.
- `/reset`/`/resethard` remain a distinct in-place mutation; `/newsession` is a
  distinct preserve-and-open; `/resume` is a distinct active-flag flip. No semantic
  overlap.
- CLI gains parity with Telegram/WS for session discovery (`--last-session`).
- **Dual-write concern removed**: rotation no longer writes to `session_registry`;
  the catalog is the only active-mapping writer (SQL `SessionRegistry` is demoted to a
  pre-catalog fallback only).

**Negative / cost**
- New port requires three adapter implementations plus Flyway migration parity
  (SQLite ↔ PostgreSQL) and an equivalent FS adapter.
- New JSON/DTO records need native-image reflection registration.
- `SessionResolutionHandler` now depends on `SessionCatalogService` (a core bean) at
  resolution time; pre-catalog sessions are lazily migrated on first touch.
- Storage growth: one catalog row per session (bounded by `archive()` and the
  existing GC/heartbeat sweep).

**Neutral**
- No change to the unified dispatch path; no new entry points that could bypass
  intent classification.

## 4. Implementation phases

1. **Port + adapters + migration parity** (no behavior change). `SessionCatalogEntry`
   compact-constructor validation; reflection registration.
2. **`SessionCatalogService` + `SessionResolutionHandler` resolver/touch wiring**
   (catalog begins populating on existing traffic; pre-catalog lazy migration).
3. **`/sessions` + `/resume` + `--last-session`/`--new-session`** (item #2), including
   `ChannelCommand`/`CommandDispatchHandler` switch extensions.
4. **`/newsession` rotation across CLI/TG/WS + `SessionRotatedEvent`** (item #1),
   including `WebSocketSessionStore.updateAmshaSession` + `WebChatEndpoint` listener.
5. **Isolation hardening + regression tests** for `/reset`/`/resethard` distinctness
   and FS rotation equivalence.

## 5. Files

**Create**
- `persistence-api/.../SessionCatalogStore.java`, `SessionCatalogEntry.java`
- `persistence-sqlite/.../SqliteSessionCatalogAdapter.java` +
  `persistence-sqlite/src/main/resources/db/migration/sqlite/V4__session_catalog.sql`
- `persistence-postgres/.../PostgresSessionCatalogAdapter.java` +
  `persistence-postgres/src/main/resources/db/migration/postgresql/V4__session_catalog.sql`
- `persistence-fs/.../FsSessionCatalogAdapter.java`
- `core/.../session/SessionCatalogService.java`, `SessionRotatedEvent.java`
- `core/.../reflex/NewSessionReflex.java`, `SessionsReflex.java`, `ResumeReflex.java`

**Modify**
- `core/.../pipeline/handlers/SessionResolutionHandler.java` (use
  `sessionCatalogService.resolveOrCreateSession`; `catalog.touch` each turn)
- `channel-api/.../ChannelCommand.java` (new permits + extend `CMD_PATTERN` **and**
  the `parse()` switch)
- `core/.../pipeline/handlers/CommandDispatchHandler.java` (**add** to handling of
  the three new `BuiltInChannelCommand` permits — exhaustive switch)
- `app/.../cli/ChatCommand.java` (`--last-session`, `--new-session`, `newSession()`)
- `channel-web/.../WebChatEndpoint.java` (listen `SessionRotatedEvent`) +
  `channel-web/.../WebSocketSessionStore.java` (add `updateAmshaSession(platformId, newId)`)
- `core/.../api/AgentSessionService.java` (`createSession` records a catalog row)
- `core/.../lifecycle/SessionReaper.java` (archive inactive catalog sessions older
  than `amsha.session-catalog.archive-after-days`; add the property to
  `application.yaml`, `.env.example`, and the core README)
- `core/.../pipeline/SessionIdResolver.java` / `SessionRegistry` / `FallbackSessionIdResolver`
  — **unchanged** (remain pre-catalog fallback only; no `repoint` added)

**Tests**
- `SessionCatalogServiceTest`, `FsSessionCatalogRotationTest` (FS parity),
  `SqliteSessionCatalogAdapterTest`/`PostgresSessionCatalogAdapterTest` (migration
  parity), `CommandDispatchHandlerNewCommandsTest` (exhaustive-switch coverage),
  `NewSessionReflexTest`, `ResumeReflexOwnershipTest`, `ChatCommandLastSessionTest`,
  `WebSessionRotatedEventTest`, `ResetReflexCatalogUntouchedTest`.

## 6. Acceptance criteria mapping

| Criterion | Where satisfied |
|---|---|
| `/newsession` preserves old session + history | `SessionCatalogService.rotate` inserts new active row, deactivates old; history untouched |
| `/history` empty in new session; old id still shows history | new uuid = empty `StatefulAgentStore`/`ChatMemoryStore`; old id unchanged |
| HITL/workspace/role do not leak across sessions | §2.5 isolation; TG/WS `flushPendingNotifications`, CLI guard |
| CLI/Telegram/UI consistent behavior + messaging | all via `SessionCatalogService` + reflexes + `SessionRotatedEvent` |
| `/reset`/`/resethard` semantics unchanged | untouched; regression test added |
| Historical sessions discoverable after restart/rebuild | catalog on same backend as checkpoints |
| No cross-user/chat session listed or resumable | `(ownerKey, channelType, channelId)` triple scoping |
| New session starts empty; selected old retains history | `resume` only flips active flag |
| **SQLite/PG/FS equivalent** | catalog is the single active-mapping writer; FS adapter exists (Critical #1 fixed) |
| `/newsession` distinct from `/reset`/`/resethard` | distinct code paths + tests |

## 7. Verification

- After Java edits: `python3 ./fix_fqns.py && ./mvnw spotless:apply -q`.
- New port/adapter parity: `./mvnw test -pl persistence-sqlite,persistence-postgres,persistence-fs`.
- Core + channel behavior: `./mvnw test -pl core,app,channel-web,channel-telegram`.
- **FS rotation equivalence** explicitly: `./mvnw test -pl persistence-fs,core -Dquarkus.profile=fs` (or the repo's FS-backend test profile).
- **Schema/registry checks:** both migrations contain the partial unique index
  `idx_session_catalog_active (... WHERE active = 1/TRUE)` (not a plain 4-tuple
  UNIQUE); `EventTypeCatalog.SESSION_ROTATED` constant exists and is used at publish;
  `SessionCatalogEntry` is registered in `CoreReflectionConfig` (and DTO rows in the
  SQLite/Postgres configs); compact constructor truncates `goalPreview` rather than
  throwing.
- **Upgrade continuity:** a regression test asserts that a user with a pre-existing
  `session_registry` row (legacy uppercase `channelType`) resolves to the SAME
  `amshaSessionId` after the catalog is introduced (resolver called with
  `ChannelType.name()`, catalog stored with `ChannelType.id()`).
- **CLI session sync:** `ChatCommandTest` asserts `currentCliSessionId` updates from
  `result.sessionId()` after a `/newsession` turn and that `/newsession`, `/sessions`,
  `/resume` appear in `extractSlashCommands()`.
- Native-sensitive path: `./mvnw compile -DskipTests -q`; `mise build:native-local` when applicable.
- Backend selection stays SQLite-default via `AMSHA_PERSISTENCE_BACKEND`.
