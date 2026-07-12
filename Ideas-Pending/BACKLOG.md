## P3 — Strategic Upgrades

These are architectural evolution items, not urgent fixes.

### Cross-Channel `/newsession` Session Rotation

**Problem:** `/reset` and `/resethard` mutate the current session, but there is
no command to start a new session while preserving the old session's persisted
history. CLI currently generates a new UUID only when the process starts;
Telegram and UI/WebSocket sessions are managed by their channel adapters.

**Direction:** Add a shared session-rotation service and a `/newsession`
command. The service must create a new session mapping while retaining the old
session in persistence, then let each adapter update its active context:

- CLI replaces its interactive session ID and continues in the same process.
- Telegram creates a new user/chat session mapping.
- UI/WebSocket receives a session-rotated event and switches its active ID.

**Acceptance criteria:**
- `/newsession` preserves the old session and its history.
- `/history` in the new session starts empty; resuming the old ID still shows its history.
- HITL checkpoints, workspace state, and role context do not leak across sessions.
- CLI, Telegram, and UI/WebSocket expose consistent behavior and messaging.
- Existing `/reset` and `/resethard` semantics remain unchanged.

### Persistent Session Catalog and Resume Picker

**Problem:** Explicit CLI resume is available with `--session-id`, but users
cannot discover historical sessions or select the most recent session. The
current session service only lists active sessions, and each channel owns its
session mapping differently.

**Direction:** Add a shared `SessionCatalog` persistence port with SQLite,
PostgreSQL, and filesystem adapters. Store owner identity, channel/chat scope,
session ID, last-used timestamp, role, and a safe goal preview. Expose this
through consistent channel commands and options:

- `/sessions` — list recent sessions scoped to the current user/chat.
- `/resume <id|number>` — select a persisted session.
- CLI `--last-session` — resume the most recent eligible session.
- CLI `--new-session` — explicitly avoid automatic last-session reuse.
- Telegram/UI session pickers use the same catalog and ownership checks.

**Acceptance criteria:**
- Historical sessions remain discoverable after restart and native rebuilds.
- No session from another user or chat is listed or resumable.
- A new session starts empty while the selected old session retains history.
- SQLite, PostgreSQL, and filesystem backends provide equivalent behavior.
- `/newsession` and existing `/reset`/`/resethard` semantics remain distinct.

### Unify Channel Dispatch and Intent Classification

**Problem:** WebSocket and Telegram messages enter through `ChannelServices` and
the shared `MessagePipeline`, while interactive CLI chat, `ask`, and some REST
operations call `AgentLoop` directly. Direct entry points can therefore skip
skill-aware intent detection, destructive-vocabulary checks, command handling,
and other pipeline behavior. This caused CLI prompts naming
`algorithmic-art` to fall back to the coordinator and then delegate to Brahma,
even though the skill was loaded.

**Direction:** Extract a shared application-level dispatch service responsible
for canonical role resolution, intent/skill classification, session context,
and agent-loop invocation. Keep channel-specific adapters for synchronous CLI
output, HITL approval, REST responses, and asynchronous WebSocket/Telegram
delivery, but make them all call the shared service.

**Acceptance criteria:**
- CLI chat, `ask`, REST, WebSocket, and Telegram use the same role-resolution path.
- Explicit role overrides remain authoritative.
- Skill names and trigger patterns resolve to `skill:<name>` consistently.
- HITL behavior and existing channel response semantics remain unchanged.
- Add entry-point tests that verify identical routing for a skill prompt.

**Status:** ✅ Implemented (branch `P106`). `TurnDispatchService.dispatch(TurnRequest)` is the single
transport-neutral entry point; `ChatCommand`, `AskCommand`, `AgentSessionService`, and
`ChannelServicesImpl` all delegate to it instead of calling `AgentLoop` directly. `SessionResolutionHandler`
preserves a caller-supplied session, and `ResumeResultHelper` cancels the CLI HITL waiter before resume so
the terminal result prints exactly once.

**Current mitigation:** Removed — CLI chat no longer performs bespoke skill-aware classification before a
direct `AgentLoop` call; it relies on the shared `IntentClassificationHandler` via `TurnDispatchService`.

### 7. Agent Loop → Graph-Based Orchestration (DAG)
After the GoF State pattern is in place (P1), the next evolution is a full directed graph model similar to LangGraph.

**Direction:**
- Nodes: `Plan`, `ExecuteTool`, `Reflect`, `HITL_Pause`, `Finish`
- Edges: conditional transitions with typed predicates
- Agent-specific sub-graphs (Researcher loops between `Search → Reflect` until confidence threshold; Coder goes straight to `Sandbox`)
- Declarative, visualizable, dynamically configurable without touching core loop

### 8. Fix ArchUnit Test Discovery Failure in `app` Module

**Problem:** Running any test in the `app` module (e.g., `SkillApiTest`) triggers an ArchUnit test discovery failure: `TestEngine with ID 'archunit' failed to discover tests`. The actual test passes, but surefire reports a build failure due to the ArchUnit engine error.

**Root Cause:** ArchUnit discovers **all** test classes on the classpath. When it loads `HitlServiceTest`, Quarkus bootstraps and runs CDI validation for the entire application context. This fails because:

1. `HitlService` has `@IfBuildProperty("quarkus.flyway.enabled", "true", enableIfMissing=true)`. The Quarkus Flyway extension's build step overrides this to `false` when no migration files are found in the `app` module's direct resources, vetoing the bean.
2. `Jdbi` is produced by `JdbiProducer` in `persistence-sqlite` with `@IfBuildProperty("amsha.persistence.backend", "sqlite", enableIfMissing=true)`. The producer depends on `AgroalDataSource`, and the cascade failure breaks the dependency chain.
3. `@IfBuildProperty` is a **build-time** annotation evaluated during Quarkus augmentation — runtime config (`application.yaml`) cannot reliably override it.

**Impact:** Cosmetic — `SkillApiTest` and all other app tests pass (5/5). The failure is only in the ArchUnit discovery side-effect. However, it blocks running `app` module tests via `mvn test -pl app` without filtering.

**Fix Options:**
- **Option A (recommended):** Move `HitlServiceTest` to the `persistence-sqlite` module where flyway + Jdbi infrastructure is properly configured.
- **Option B:** Exclude `HitlServiceTest` from ArchUnit discovery via `@AnalyzeClasses(packages = ...)` configuration.
- **Option C:** Add `@Vetoed` to `HitlServiceTest` and use `@InjectMock` instead of `@Inject` for `HitlService` and `Jdbi`.

**Verification:** After fix, `./mvnw test -pl app` should complete without ArchUnit discovery errors.

**Status:** ✅ Resolved — no longer reproducible. Running the full `app` module test suite
(454 tests) completes without any `TestEngine with ID 'archunit' failed to discover tests`
error. The precondition in the root cause no longer holds: the `app` module carries no
migration files of its own, but Flyway migrations are on the test classpath via the
`persistence-sqlite` dependency, so `quarkus.flyway.enabled` resolves to `true` and
`HitlService` (declared with `enableIfMissing = true`) loads normally. As defensive
hardening against regression, `quarkus.flyway.enabled: true` is now set explicitly in
`app/src/test/resources/application.yaml`. The recommended code move (Option A) is not
needed because it would break the module layering (`HitlService` lives in `app`, which
`persistence-sqlite` does not depend on).

---

## Backlog note

You can write it like this:

**Backlog item:** Move tool execution category from `ToolCapability` fallback logic to the tool contract itself (`AgentTool.category()` or `ToolMetadata`) so dynamically discovered MCP tools can declare `NETWORK`, `COMPUTE`, or `DATABASE` explicitly instead of defaulting to `INTERNAL`. 

## Suggested scope

This backlog item should cover:
- Static built-in tools continue to map from `ToolCapability.category()`.
- Dynamic MCP tools expose category through discovery metadata.
- `ToolCategoryMapper` becomes a compatibility shim or is removed.
- Executor selection uses the tool’s declared category end-to-end. 

## Why this is the right backlog

That framing keeps the current implementation honest: it is good enough today, but it is not the final extensible shape. It also prevents future readers from assuming the fallback-to-internal behavior is an architectural choice rather than a temporary bridge. 

## Short ADR text

**Decision:** Keep `ToolCapability` as the canonical category source for built-in tools only.  
**Reason:** Dynamic MCP tools currently bypass the enum and safely default to `INTERNAL`.  
**Consequence:** No starvation risk now, but category metadata must move to the runtime tool contract when dynamic tools become a larger share of the system. 
