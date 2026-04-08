# ArcClaw Architecture Invariants

## Purpose

This document defines the architecture rules that MUST hold across ArcClaw.
These are not suggestions, style preferences, or historical notes.
If a rule here is violated, the code is considered incorrect even if tests appear to pass.

## How to use this file

- Read this before changing architecture-sensitive code.
- Prefer enforcing these rules in code, tests, build steps, startup validation, or helper APIs.
- If a rule can be automated, automate it and then simplify this file.
- Do not add temporary bug workarounds here unless they are currently required for safe operation.

## Severity

- MUST: mandatory invariant, never violate.
- MUST NOT: forbidden invariant, never do this.
- SHOULD: strong default; deviations require explicit comment and reason.
- LEGACY: tolerated temporarily, must be removed or formalized later.

---

## 1. Module boundaries

### 1.1 Deployment module
- `arcclaw-deployment` is build-time only.
- MUST NOT place runtime business logic, request handling, persistence, tool logic, or channel behavior there.
- Build steps may generate metadata/resources only.

### 1.2 Core module
- `arcclaw-core` owns agent runtime, workspace/session lifecycle, governance, persistence, skills, structured output, reflexes, audit, sandbox contracts, and generic pipeline behavior.
- `arcclaw-core` MUST NOT depend on Telegram-specific behavior or any single channel assumption.

### 1.3 App module
- `arcclaw-app` owns application wiring, REST resources, startup bootstrap, config producers, and current adapter registration.
- App-layer code SHOULD stay thin.
- Business rules belong in core unless they are truly transport/framework specific.

### 1.4 Channel abstraction
- Channel-neutral flows MUST NOT default missing channel metadata to a specific channel implementation.
- New channels must fit the channel SPI without changing core engine behavior.
- Any fallback to Telegram is LEGACY and should be removed, not copied.
- **P82-85 SPI Enforcement:** Channel modules (e.g., `arcclaw-channel-telegram`, `arcclaw-channel-web`) MUST NOT import `com.arcclaw.core.*` directly. 
  - Interaction with core HITL state MUST flow through the `com.arcclaw.channel.api.hitl.ApprovalGateway` SPI.
  - Querying pending HITL approval state MUST flow through the `com.arcclaw.channel.api.hitl.PendingApprovalChecker` SPI.
  - Identity validation (API keys) MUST flow through `com.arcclaw.channel.api.ChannelServices.isValidIdentity()`. 
  - Direct injection of core classes like `com.arcclaw.core.security.SessionOwnerResolver` or `com.arcclaw.core.state.StatefulAgentStore` in channel modules is FORBIDDEN as it violates architectural boundaries and fails CDI resolution in module-level tests.


### 1.5 Channel type policy
- POLICY: WorkspaceContext.channelType() returns Optional<String>. Use requireChannelType() only at call sites where a missing channelType is a fatal invariant violation. Use channelType().orElse("UNKNOWN") at call sites where a fallback is acceptable (e.g., audit logging only).

---

## 2. Context and session binding

### 2.1 Per-session state
- MUST use scoped/session-aware context primitives for per-session data.
- MUST NOT store per-session mutable state in `@ApplicationScoped` fields.

### 2.2 Two context layers
ArcClaw has two distinct binding layers:

1. `WorkspaceContext`
   - Used for tool execution, agent loop, business logic, and runtime execution path.
2. `WorkspaceScopeHolder`
   - Used for `@WorkspaceScoped` activation and reflex/command-dispatch path.

These are NOT interchangeable.

### 2.3 Tool path rule
- Any class reachable from:
  `AgentDispatchHandler -> AgentLoop -> ToolRegistry -> tool.execute()`
  MUST use `WorkspaceContext` accessors.
- Tools MUST NOT read user/chat/session from `WorkspaceScopeHolder.*.get()`.

### 2.4 Reflex path rule
- Reflexes invoked through `CommandDispatchHandler` may use `WorkspaceScopeHolder`.

### 2.5 Allowed exception
- The audit-drain fallback in tool-resolution path is the only legitimate place where context fallback may occur outside normal binding.

### 2.6 Test mirroring
- Tool tests MUST bind with `WorkspaceContext.run(...)`.
- Reflex tests MUST bind with `WorkspaceScopeHolder.call(...)`.
- To test unauthorized/unbound-like behavior, use blank values intentionally rather than silently omitting required binding.

### 2.7 Asynchronous Thread Context Propagation (§2.7 INVARIANT)
- Any asynchronous execution that schedules work on a different thread (e.g., via `ContextAwareExecutorService`) MUST explicitly capture and propagate all 6 context identifiers from `WorkspaceContext`: `sessionId`, `userId`, `chatId`, `autonomyLevel`, `agentRole`, and `channelType`.
- The captured context MUST be restored on the background thread using the comprehensive `WorkspaceContext.run(...)` overload before invoking the background task.
- Failure to propagate the complete context results in loss of identity, breaking role-based access control, tool governance, and audit logging for background tasks (e.g., cron jobs, background MCP execution).

> **`AsyncPersistenceEngine` exception:** The drain threads of `AsyncPersistenceEngine`
> are exempt from the 6-context `WorkspaceContext.run(...)` requirement because they
> process events from many sessions simultaneously. They bind only `WorkspaceScopeHolder`
> per event (session scope), not the full workspace context. This is intentional and is
> the only legitimate multi-session background binding pattern. See §5.17.

---

## 3. Runtime execution invariants

### 3.1 Agent loop boundaries
- `AgentLoop` orchestration must remain distinct from tool execution, checkpoint persistence, and message transport.
- Do not move transport-specific logic into the core state machine.

### 3.2 Tool invocation path
- All tool execution MUST flow through `ToolRegistry` and `ToolInvoker`.
- Do not bypass guard, audit, sanitization, or approval logic by calling tool implementation classes directly.

### 3.3 Tool observation sanitization
- Every tool response MUST be sanitized before history append.
- No tool type is exempt, including GitHub, MCP, sandbox, or internal tools.

### 3.4 Approval handling
- Approval-required executions MUST persist checkpoint state before exiting the loop.
- Resume/deny behavior must use the checkpoint lifecycle, not ad hoc retry logic.
- The double-resume guard (`StatefulAgentStore.markBatchResuming()`) MUST remain in
  place. It prevents two concurrent approval signals (e.g., Telegram callback + REST)
  from both triggering `AgentLoop.run()` simultaneously for the same session.
  `clearBatchResuming()` MUST be called in the `finally` block of the resume path and
  by `WorkspaceManager.terminateSession()`.
- MUST NOT call `resumeCheckpoint()` / `AgentLoop.run(isResumeSignal=true)` for 
  denial decisions. Denial is a termination, not a resume. A dedicated 
  `rejectAndTerminate()` path MUST mark the checkpoint FAILED, notify the user 
  via channel, and call `WorkspaceManager.terminateSession()` without entering 
  the agent loop.
- `HitlService.resolve()` MUST call `resumeCheckpoint()` (for approvals) or 
  `rejectAndTerminate()` (for denials) after the decision update. The decision 
  methods (`approveTool`, `denyAll`) update DB state only — they do NOT trigger 
  resume themselves.

### 3.5 Structured retry budget behavior
- Soft budget warnings may be logged and tolerated for retry behavior.
- Hard budget exceedance must propagate upward and stop execution.
- Do not swallow hard budget failures.

## 3.6 Chat memory storage boundary

System-role messages (injected tool rules, persona definitions, sanitized tool output headers)
MUST NOT be stored in the SQLite `ChatMemoryStore`. Only user and assistant turns are persisted.
Storing system messages inflates context, corrupts compaction logic, and leaks tool contracts.

---

## 3.7 Budget threshold check ordering

In `StructuredRetryLoop`, `assertWithinBudget()` threshold check MUST precede `compareAndSet()`.
If CAS fires first at 79% budget, `warningSent` is permanently `true` — the real 80% boundary
warning is forever suppressed. The ordering is: check → log → CAS, never CAS → check.

---

## 4. Registry and SPI invariants

### 4.1 Registry discovery
- Registries that discover CDI beans are authoritative integration points.
- Duplicate logical registrations MUST fail fast at startup where feasible.

### 4.2 Tool naming
- `ToolRegistry.invokeTool()` is string-keyed.
- Any typed capability enum must bridge through canonical tool-name accessors.
- Do not compare external tool names against enum constant names.

### 4.3 Skill-first routing
- `SkillAwareIntentClassifier` MUST check `SkillRegistry` before LLM fallback.
- Do not invert that order.

### 4.4 Role naming
- Internal role identity is the role name, not deity display text.
- Alias handling belongs in parsing/resolution code, not scattered across business logic.
- Template selection over roles must remain exhaustive.
- **P82 Data-Driven Personas:** Agent personas are collapsed into the `DefaultAgent` implementation. System prompts MUST be loaded from `src/main/resources/templates/personas/{role}.txt` via Qute. Class-per-persona inheritance is FORBIDDEN.


### 4.5 Channel plugin contract
- New channel implementations must satisfy the channel SPI contract cleanly.
- Do not add one-off channel branches into core flow when an SPI extension point exists.

## 4.6 ReflexRegistry chain ordering

The `ReflexRegistry` discovery uses a two-pass chain that MUST NOT be broken or reordered:
- Pass 1: Built-in command prefixes (approve, deny, status, cron-list, etc.)
- Pass 2: CDI-discovered `AgentReflex` beans (additive, no core changes needed)

Do NOT inject reflexes into Pass 1, or reorder the passes. New reflexes go to Pass 2 only.

## 4.7 Skill frontmatter boundary

`maxBudgetUsd` is MUST NOT appear in skill frontmatter YAML.
Budget governance is a runtime session concern owned by `GovernanceLayer`, not a per-skill config.
Adding it to skill YAML silently bypasses the governance SPI.

## 4.8 Dynamic skill rendering contract

Dynamic skills MUST render their system prompt via the pre-compiled Qute wrapper
`SkillSystemPrompt.html` only. Ad hoc string-built prompts bypass injection-safety
sanitization and native-image Qute compilation checks.

## 4.9 AgentFunctionProcessor schema source

`AgentFunctionProcessor` MUST generate JSON schema from the SAM **return type** using
`SchemaFactoryWrapper.acceptJsonFormatVisitor(returnTypeClass, visitor)`.
MUST NOT use `ToolSpecifications.toolSpecificationFrom()` — that generates the INPUT
parameter schema, not the output schema, and produces silent wrong behavior in native tools.

## 4.10 Delegation Capability Guard

`DelegateToAgentTool` MUST validate that the target role's capabilities are a subset of the delegating role's capabilities before constructing the sub-agent loop. Delegation that would grant the sub-agent capabilities the parent role does not have MUST be rejected with a descriptive tool observation. Transitive capability escalation via delegation chains is FORBIDDEN.

- The scheduling tools (`schedule-cron`, `delete-cron`, `list-crons`) are EXEMPT from
  HITL approval checks regardless of the delegating role's autonomy level, because
  scheduling is metadata-only and does not execute arbitrary tool actions.
  This exemption is encoded in `ToolInvocationGuard` and MUST NOT be removed when
  adding new scheduling-adjacent tools without explicit review.

## 4.11 Intent Routing Negation Safety

Fast-path routing patterns in IntentRoutingProvider implementations
MUST NOT match negations of the intent they detect (e.g. 'do NOT
schedule' must not route to coordinator as a scheduling request).
If a pattern is broad enough to match negations, implement
IntentRoutingProvider.shouldSkip(String userMessage) with a
static final Pattern negation guard. Never compile patterns
inline — use static final Pattern per §9.1.

---

## 5. Persistence and transaction rules

### 5.1 SQLite transaction shape
- SQLite WAL mode requires short, tight transaction boundaries.
- Do not hold long transactions across slow I/O, LLM calls, or channel operations.

### 5.2 Schema evolution
- Regular entity-table evolution is entity-driven.
- Raw DDL is reserved for virtual tables, triggers, and indexes.
- Do not introduce ad hoc migration scripts for normal table changes unless the architecture policy is explicitly changed repository-wide.

### 5.3 Repository style
- Core repositories use `EntityManager`.
- Do not introduce Panache repository/entity patterns into cross-module persistence paths.

### 5.4 ORM 7 transaction behavior
- Be careful with `QuarkusTransaction.requiringNew().run(...)` lambda boundaries.
- Prefer dedicated `@Transactional(REQUIRES_NEW)` methods **at the outermost write boundary** when proxy binding matters.
- **MUST NOT** nest a `@Transactional(REQUIRES_NEW)` method inside a call stack that already holds an active `@Transactional(REQUIRED)` or `@Transactional` transaction when using SQLite — see §5.8. The `REQUIRES_NEW` must be the outermost transaction boundary for each write unit, not a nested child inside a longer-lived `REQUIRED` context.
- Do not assume `em.merge()` is the right fix for already-managed entities.

### 5.5 Read visibility in tests
- Test writes must be committed before REST assertions that depend on them.
- Snapshot isolation assumptions in SQLite tests are easy to get wrong; prefer explicit cleanup and committed setup.

### 5.6 Identity / dialect
- The custom SQLite dialect is mandatory for ORM-generated identity-column DDL correctness.
- Do not remove or bypass it for entity tables using generated identifiers.

## 5.7 EncryptedSecret version field ownership

`EncryptedSecret.ormVersion` is Hibernate-managed. Application code MUST NOT read or set it.
`EncryptedSecret.encryptionKeyVersion` is application-managed. Application code sets ONLY this field.
Mixing these two fields corrupts encryption version tracking and is undetectable at runtime.

---

## 5.8 SQLite Single-Writer Constraint (CRITICAL — ROOT CAUSE OF P84 BUSY CASCADE)

SQLite WAL mode allows any number of concurrent readers but **only one writer at a time**.
This is a database engine constraint, not a configuration choice.

**The forbidden nesting pattern:**
```
@Transactional(REQUIRED)               ← acquires Connection-1, starts XA BEGIN IMMEDIATE
  └─ calls agentStore.updateXxx()
       └─ withRetry(...)
            └─ self.doUpdateXxx()
                 └─ @Transactional(REQUIRES_NEW)  ← acquires Connection-2, tries COMMIT
                      └─ SQLITE_BUSY — Connection-1 holds the reserved write lock
```

`REQUIRES_NEW` suspends the outer `REQUIRED` transaction but **keeps its Agroal connection
open with a reserved SQLite write lock**. The inner `REQUIRES_NEW` borrows a second
connection and attempts `COMMIT`. SQLite cannot grant the exclusive write lock required
for `COMMIT` while Connection-1 holds the reserved lock → `SQLITE_BUSY`.

**The correct pattern:**
```
@Transactional(NOT_SUPPORTED)          ← no transaction, no reserved lock
  └─ calls agentStore.updateXxx()
       └─ withRetry(...)
            └─ self.doUpdateXxx()
                 └─ @Transactional(REQUIRES_NEW)  ← gets its own clean connection, COMMIT succeeds
```

MUST NOT: Annotate any method `@Transactional(REQUIRED)` (or bare `@Transactional`)
if that method directly or transitively calls any `@Transactional(REQUIRES_NEW)` method.

MUST NOT: Accept "it worked in JVM mode" as proof of safety. Agroal pool timing
differs under load and between JVM and native image startup sequences.

---

## 5.9 Transaction-Free Zone: Long-Running Operations

Any operation that performs slow I/O (LLM network calls, external REST, arbitrary sleep,
full agent loops) MUST NOT run inside an active JTA transaction.

The following are **transaction-free zones** — no `@Transactional` annotation may exist
on them OR on any caller up the CDI call stack:

| Class / Method | Reason |
|---|---|
| `AgentLoop.run()` and all descendants | LLM call takes 5–60s; pins a connection |
| `CheckpointResumeHandler.resumeAfterBatchDecision()` | Calls `AgentLoop.run()` |
| `ReasoningEngine.nextStep()` | External LLM HTTP call |
| `ToolRegistry.invokeTool()` and `AgentTool.execute()` | May call external APIs |
| `ApprovalStateMachine.decide()` | Calls both REQUIRES_NEW writes AND AgentLoop resume |
| `CheckpointApprovalService.approveTool/denyTool/approveAll/denyAll` | Calls REQUIRES_NEW writes then resume |

Holding a JTA transaction open across a 30-second LLM call pins one Agroal connection
for the entire duration. Every concurrent write — audit drain, approval, cron scheduler —
is blocked until that connection is released.

---

## 5.10 `CheckpointApprovalService` Transaction Contract (IMMUTABLE)

`CheckpointApprovalService` sits at the boundary between the HITL approval path
(synchronous reflex) and the agent loop resumption (long-running, transaction-free).

| Method | Required Annotation | Reason |
|---|---|---|
| `approveTool()` | `@Transactional(NOT_SUPPORTED)` | Calls `updateBatchToolDecision` (REQUIRES_NEW) |
| `denyTool()` | `@Transactional(NOT_SUPPORTED)` | Same |
| `approveAll()` | `@Transactional(NOT_SUPPORTED)` | Same |
| `denyAll()` | `@Transactional(NOT_SUPPORTED)` | Same |
| `triggerResume()` | None / not annotated | In the transaction-free zone (§5.9) |
| `persistBatchWithPreExecuted()` | Via `agentStore.save()` (REQUIRES_NEW) | Inherits REQUIRES_NEW from store |
| `HitlService.resolve()` | `@Transactional(NOT_SUPPORTED)` | App-layer entry point for UI approval;
|                         |                                 | delegates directly to approveTool() which
|                         |                                 | is already NOT_SUPPORTED. An outer REQUIRED
|                         |                                 | tx here re-introduces the REQUIRED→REQUIRES_NEW
|                         |                                 | deadlock via the app→core call path. |

MUST NOT change any of these to `@Transactional` or `@Transactional(REQUIRED)`.
Doing so re-introduces the SQLITE_BUSY failure pattern described in §5.8.
This contract is non-negotiable and must be re-verified after every major refactor.

---

## 5.11 `ApprovalStateMachine.decide()` Transaction Contract

`ApprovalStateMachine.decide()` (the `ApprovalGateway` SPI implementation) MUST be
annotated `@Transactional(NOT_SUPPORTED)` or carry no transaction annotation.

It calls:
1. `agentStore.updateBatchToolDecision()` — `REQUIRES_NEW`
2. `agentStore.isAllBatchToolsDecided()` — `REQUIRES_NEW` read
3. `resumeHandler.resumeAfterBatchDecision()` — transaction-free zone

An outer `@Transactional` wrapping the entire `decide()` call simultaneously violates
the REQUIRES_NEW nesting rule (§5.8) and the transaction-free zone rule (§5.9).

---

## 5.12 `AgentCronScheduler` Isolation Contract

`AgentCronScheduler.pollAndDispatch()` MUST be annotated `@Transactional(NOT_SUPPORTED)`.

Each sub-operation (`reclaimStaleCrons`, `claimForFire`, `setDeliveryId`) is a dedicated
`@Transactional(REQUIRES_NEW)` method in its repository. The scheduler frame MUST NOT
open an outer transaction that outlives these individual write units.

Additionally, `reclaimStaleCrons()` MUST be wrapped in an isolated `try/catch` that
allows the poll cycle to continue even when one reclaim attempt fails:

```java
// CORRECT in AgentCronScheduler.pollAndDispatch()
try {
    cronRepository.reclaimStaleCrons();
} catch (Exception e) {
    log.warnf("Zombie-cron reclaim failed (skipping cycle): %s", e.getMessage());
    return; // skip this poll cycle cleanly
}
```

Without this isolation, a `SQLITE_BUSY` or Narayana XA state corruption from a
concurrent approval write (§5.8) cascades into the cron poll cycle and can abort
the entire scheduler thread's JTA context, causing subsequent unrelated writes
to fail with "cannot commit - no transaction is active."

---

## 5.13 Agroal Pool Configuration Invariants

The following Agroal settings MUST be present in `application.properties`
for all non-test profiles:

```properties
quarkus.datasource.jdbc.background-validation=true
quarkus.datasource.jdbc.background-validation-interval=PT30S
quarkus.datasource.jdbc.validation-query-sql=SELECT 1
quarkus.datasource.jdbc.max-size=3
```

**Why background-validation is mandatory:**
When a `REQUIRES_NEW` transaction fails with `SQLITE_BUSY`, Narayana attempts XA
rollback. SQLite may report "cannot rollback - no transaction is active" (the connection
already auto-aborted after `busy_timeout`). Narayana marks the XA branch as failed.
Without `background-validation`, Agroal returns this broken connection to the pool.
The next caller — completely unrelated to the approval that failed — receives the broken
connection and gets "cannot commit - no transaction is active" even though its own code
is correct. This is the cron cascade failure mode observed in P84.

**Why max-size must be ≥ 3:**
- Slot 1: `AsyncPersistenceEngine` critical drain thread (long-held, `REQUIRES_NEW`)
- Slot 2: `AsyncPersistenceEngine` telemetry drain thread
- Slot 3: Foreground approval writes / cron `REQUIRES_NEW` operations

A pool of 2 starves foreground approval writes whenever both drain threads are active.

---

## 5.14 `SqliteRetryTemplate` Nesting Contract

`SqliteRetryTemplate.execute()` is designed for one specific pattern:
a single `@Transactional(REQUIRES_NEW)` CDI proxy self-call, retried N times with
exponential backoff on `SQLITE_BUSY` errors.

MUST NOT call `SqliteRetryTemplate.execute()` from inside an active JTA transaction
(`REQUIRED` or `MANDATORY`). The retry loop assumes each attempt gets a fresh,
uncontested write lock — an outer transaction's reserved lock ensures every attempt
will fail with `SQLITE_BUSY`, consuming all retries uselessly.

MUST NOT use `SqliteRetryTemplate` for:
- Non-database operations
- Read-only queries
- Any operation that is not a `@Transactional(REQUIRES_NEW)` CDI proxy call

The template inspects `SqliteErrorStrings.isBusy(e)` across the full `getCause()` chain
because Hibernate wraps `SQLiteException` several layers deep. Do not simplify this check
to a single `e.getMessage()` string comparison.

---

## 5.15 Narayana XA Failure Cascade Prevention

When a `REQUIRES_NEW` transaction fails with `SQLITE_BUSY`:

1. SQLite auto-aborts the failing connection after `busy_timeout` ms.
2. Narayana attempts XA rollback on the failed branch — SQLite reports
   "cannot rollback - no transaction is active" (already aborted).
3. Narayana marks the XA branch as rolled back but the connection state is
   inconsistent.
4. **Without `background-validation`:** Agroal returns this broken connection to the
   pool. The next caller gets "cannot commit - no transaction is active" for its
   own innocent transaction.
5. **Cascade effect:** If the cron scheduler poll cycle's `reclaimStaleCrons()` owns
   the broken connection, the entire scheduler Narayana TX context is poisoned, and
   every `cronRepository` write in that cycle fails — even `claimForFire()` for
   completely unrelated jobs.

**Three prevention layers — ALL must be in place simultaneously:**

| Layer | Rule | Prevents |
|---|---|---|
| §5.8 | No REQUIRED → REQUIRES_NEW nesting | Eliminates BUSY at source |
| §5.13 | `background-validation=true` | Catches broken connections before reuse |
| §5.12 | `reclaimStaleCrons` isolation try/catch | Prevents cron cascade from approval failures |

If all three layers are in place, a single BUSY event is isolated to the failing
operation and cannot cascade to unrelated callers or threads.

---

## 5.16 `WorkspaceManager` GC Method Transaction Annotations

`WorkspaceManager.gcSweep()` MUST be `@Transactional(NOT_SUPPORTED)`.
`WorkspaceManager.findAwaitingApprovalIds()` MUST be `@Transactional(NOT_SUPPORTED)`.
`WorkspaceManager.sweepTimedOutApprovals()` MUST be `@Transactional(NOT_SUPPORTED)`.

These are scheduled GC methods that delegate to individual `REQUIRES_NEW` write units.
Wrapping them in `REQUIRED` would create a long-lived connection that holds a write lock
for the entire GC sweep duration (potentially scanning many sessions).

`WorkspaceManager.markApprovalTimedOut()` MAY be `@Transactional(REQUIRED)` only if it
does NOT call any `REQUIRES_NEW` repository method. If per-tool decision writes are added
to this path in future, it must be demoted to `NOT_SUPPORTED`.

---

## 5.17 `AsyncPersistenceEngine` Context Binding Rule

`AsyncPersistenceEngine` runs two virtual drain threads (`arcclaw-audit-critical-drain`,
`arcclaw-audit-telemetry-drain`). These are NOT CDI-managed threads.

**MUST:**
- Bind `WorkspaceScopeHolder.run(event.getSessionId(), ...)` **per event** inside the
  drain loop, never once per drain thread.
- Use `QuarkusTransaction.requiringNew().run(...)` directly in `persistSynchronously()`
  because the drain thread is outside the CDI proxy chain.

**MUST NOT:**
- Bind `WorkspaceContext.run(...)` (the 6-value full context) in the drain loop. Drain
  threads process events from many different sessions. Binding one session's full context
  for the thread's lifetime breaks isolation between sessions.
- Call `WorkspaceScopeHolder.run(...)` once at drain thread startup and assume it covers
  all events. Each event owns its own scope binding.

`persistSynchronously()` is `@Transactional(REQUIRES_NEW)` via CDI self-inject (`self`),
satisfying the proxy binding requirement for Narayana to manage the XA branch correctly.

---

## 5.18 `doMarkCheckpointsFailedRequiresNew` Terminal Status Exclusion

`WorkspaceManager.doMarkCheckpointsFailedRequiresNew()` MUST exclude ALL terminal statuses
from the `FAILED` overwrite query. The exclusion set is:
`FAILED`, `COMPLETED`, `ARCHIVED`, `COMPROMISED`.

**Why this matters (P84 race condition):**
`AgentCronScheduler` calls `WorkspaceManager.terminateSession()` immediately after
`CronResultHandler.markCompleted()`. If the `COMPLETED` finalization `REQUIRES_NEW`
transaction has not fully flushed to disk before the `terminateSession` query runs
(possible under concurrent load with Agroal pool pressure), the checkpoint may still
appear as `IN_PROGRESS` in the EntityManager's first-level cache. Without the exclusion,
it gets incorrectly overwritten to `FAILED`, corrupting the audit trail and
triggering spurious failure notifications.

MUST NOT add new terminal statuses to `CheckpointStatus` without updating this query.

---

## 5.19 Checkpoint Query Ordering — ORDER BY id DESC

Any JPQL or native query that retrieves "the most recent" checkpoint for a session
MUST include `ORDER BY c.id DESC LIMIT 1`.

**Why:** SQLite does not guarantee insertion order without an explicit ORDER BY. Without
`ORDER BY c.id DESC`, a `LIMIT 1` may return the oldest COMPLETED row, shadowing a
newer AWAITING_APPROVAL checkpoint. This produces the silent failure:
"No pending tool executions require approval" when there is one.

This applies to:
- `findBySessionAndRole(sessionId, agentRole)` — internal to `StatefulAgentStore`
- `findLatestAwaitingApproval(sessionId)` — exposed on `StatefulAgentStore` interface
- `findLatestAwaitingApprovalForCron(cronId)` — cron approval lookup

MUST NOT use `getFirstResult()` or `stream().findFirst()` without an explicit ORDER BY.

---

## 6. Native image and reflection rules

### 6.1 Reflection safety
- New classes/records/enums used reflectively in native mode must be registered when Quarkus does not detect them automatically.
- JVM success does not prove native safety.

### 6.2 Qute native rule
- Any class used as `.data("key", object)` in Qute and accessed by property in native mode must be `@TemplateData`.
- `@TemplateData` and reflection registration solve different problems; do not treat them as interchangeable.

### 6.3 Generated resources
- Build-time schema/resource generation must emit Quarkus generated resources, not write arbitrary files to the filesystem.

### 6.4 Resource loading
- For generated/augmented resources in multi-module tests, prefer context-classloader resource loading when required by current Quarkus augmentation behavior.

## 6.5 @RunOnVirtualThread enforcement

The constraint that JAX-RS resources injecting `EntityManager` MUST have `@RunOnVirtualThread`
is enforced at **build time** by `RestResourceBuildStep`. Violations fail the build with a
`ValidationError` — they do NOT surface as runtime `BlockingOperationNotAllowedException`.
Do not remove `RestResourceBuildStep` thinking it is optional infrastructure.

### 6.6 Static run-time objects
- MUST NOT store JDK or library objects that require run-time initialization (e.g. `java.net.http.HttpClient`, `java.lang.Thread`, or descriptors) in `static` or `static final` fields when building for native-image.
- GraalVM attempts to store these objects in the "image heap" at build time, leading to `UnsupportedFeatureException` (An object of type ... was found in the image heap).
- Use `@ApplicationScoped` instance fields or `@PostConstruct` lazy initialization to ensure these objects are created at run-time.
- **6.7 Module-specific Reflection Config**: New modules that introduce CDI beans, DTOs (for JSON/REST), or external libraries that use reflection MUST include a dedicated `*ReflectionConfig` class annotated with `@RegisterForReflection` in a `.config` subpackage. This centralizes reachability for GraalVM and prevents dead-code elimination of critical runtime components across module boundaries. JVM-mode success is NOT evidence that these registrations are unnecessary.


---

## 7. Channel and transport rules

### 7.1 Channel neutrality
- Generic dispatchers, events, and registries MUST remain channel-neutral.
- Missing metadata must not silently imply a specific channel.

### 7.2 Notification dispatch
- Cron completion and approval timeout dispatch must route by declared channel type.
- Any legacy fallback behavior must be isolated and marked for deletion.

### 7.3 MCP framing
- MCP stdio transport is newline-delimited JSON-RPC.
- Every outbound message must be newline framed and flushed.
- Inbound reading must be line-based.

### 7.4 Channel daemons
- Channel daemons must be safe when unconfigured.
- `isConfigured()` exists to prevent startup crashes due to absent optional channel config.

## §7. Parallel Tool Execution

7.1 MUST: A parallel LLM turn (N tool calls in one ChatResponse) MUST
    be represented as a single ReasoningStep in agent history using
    action = ReasoningStep.PARALLEL_SENTINEL ("__parallel__").
    The full batch payload is stored in actionInput under key "batch".

7.2 MUST NOT: Expand parallel tool calls into N separate ReasoningStep
    entries in history before execution. History entries MUST map
    1:1 with LLM turns to preserve chat protocol correctness.

7.3 MUST: ChatHistoryAssembler MUST reconstruct a parallel turn as:
    ONE AiMessage(thought, [req1, req2, ..., reqN]) followed by
    N ToolExecutionResultMessages in callId order.

7.4 MUST: If ANY tool in a parallel batch requires HITL, ALL tools in the batch pause atomically.
    Per-tool approval IS supported via `approve <toolName>` / `deny <toolName>` reflex commands
    and Telegram inline keyboard buttons (one row per pending tool). Pre-executable tools
    (those safe to run under `FULL` autonomy before the pause) are executed immediately
    before the checkpoint is persisted and are excluded from the approval keyboard.
    The approval keyboard only renders tools whose decision is still `PENDING`.

7.5 MUST NOT: Execute parallel tools on the calling thread.
    Use ManagedExecutor (already injected via WorkspaceExecutors)
    for all parallel tool invocations.

7.6 MUST: All parallel tool spans MUST be siblings under the same
    parent iteration span. Use startToolSpan() per tool call.

7.7 MUST: Deduplication guard (ToolInvocationGuard) MUST check ALL
    tools in a batch before ANY tool executes. If any call is a
    duplicate, the entire batch is evaluated before execution begins.

7.8 MUST: ParallelToolCall.callId MUST follow the pattern "call_N"
    where N is the 0-based index in the LLM's tool call list.
    Do NOT use UUID for callIds — LangChain4j requires stable,
    predictable callId matching between AiMessage and
    ToolExecutionResultMessage.

7.9 MUST NOT: Modify `ReasoningStep` record shape without a corresponding migration
    in `StatefulAgentStore` (both `doSaveSteps` and `doSaveAndFinalize`) and in
    `ChatHistoryAssembler`. The parallel batch is encoded in `actionInput["batch"]`
    as a JSON array. Introducing a native parallel column requires schema migration
    and full test coverage of the serialization round-trip.

7.10 SHOULD: Test parallel tool execution with Claude 3.5 Sonnet and
     GPT-4o before enabling in production, as parallel tool call
     behavior differs between providers. Groq (llama-3.x) rarely
     issues parallel tool calls — most parallel call traffic will
     come from Anthropic and OpenAI providers.

7.11 MUST NOT: Re-introduce a single-tool fast-path in `ReasoningStepParser`.
     All LLM tool-call responses are processed as a list (N ≥ 1).
     The `getFirst()` fallback was removed and MUST NOT be restored
     as a silent fallback — it hides multi-tool responses from the batch pipeline.

7.13 MUST: OpenTelemetry span context MUST be explicitly propagated
     to virtual threads. Capture io.opentelemetry.context.Context.current()
     on the calling thread BEFORE submitting to the executor. Restore it
     inside each virtual thread via context.makeCurrent(). ContextAwareExecutorService
     propagates WorkspaceScoped session context (ScopedValue) only — it
     does NOT propagate OTel ThreadLocal spans.
     (P81 Confirmation: Verified in ParallelToolStepExecutor.java lines 173/192)

7.14 INVARIANT: ReasoningStep history is the in-memory source of truth
     during AgentLoop.runWithRole() execution. ChatHistoryAssembler reads
     from in-memory history on each LLM call — SQLite does not need to
     be current mid-run. AgentLoop persists history to SQLite via
     agentStore.save() at session end (Finished/Failed/LoopExceeded) or
     HITL pause (AwaitingApproval checkpoint). ChatMemoryWriter manages
     user goal/AI answer messages in ChatMemoryStore only — it MUST NOT
     be used for ReasoningStep history persistence.

7.15 MUST NOT: A deduplicated tool in a parallel batch MUST NOT cause
     its peers to be skipped. The dedup tool receives its cached
     observation as its result. All other tools in the batch proceed
     to execution normally.

7.16 SHOULD: Each parallel tool execution MUST time out after
     arcclaw.parallel.tool-timeout-seconds (default: 60). A timed-out
     tool returns withResult("Tool timed out after Xs", false) and
     does not block the rest of the batch.

7.17 MUST: Metrics counters arcclaw.agent.tool.calls MUST reflect
     individual tool names in a parallel batch — not the __parallel__
     sentinel. The MetricsAgentObserver already receives individual
     onStepComplete() calls from ParallelToolStepExecutor (one per
     tool) — verify the sentinel step itself does NOT trigger an
     additional __parallel__ counter increment.

7.18 INVARIANT: ReasoningStep history list is mutated EXACTLY ONCE
     per parallel batch execution — inside buildCompletedBatchDecision().
     Do NOT add intermediate steps for dedup results or partial
     executions. The single batch step encodes ALL N results.

7.19 INVARIANT: ParallelToolCall.callId() MUST be non-null at the point
     the merge loop executes. ReasoningStepParser MUST generate a
     UUID fallback when ToolExecutionRequest.id() is null (Ollama). 
     Never use .equals() on a potentially-null callId — use Objects.equals().

7.20 INVARIANT: A parallel batch HITL pause stores per-tool decision
     state in AgentCheckpoint.batchToolDecisions (TEXT, JSON) as:
       { "toolName": "PENDING" | "APPROVED" | "DENIED" }
     toolName is the key (not callId) because tool names appear in
     user-facing commands: `approve github`, `deny manage_schedule`.

7.21 MUST: `approve` / `deny` with no argument MUST behave identically
     to P70/P71: approve or deny ALL pending tools atomically.
     Per-tool commands only activate when a tool name argument is given
     AND the current checkpoint has a batchToolDecisions field.

7.22 INVARIANT: A partially-decided parallel batch (some tools APPROVED,
     some PENDING) MUST NOT resume. Resume fires only when ALL tools in
     the batch have a non-PENDING decision. Pending tools not explicitly
     decided when `approve` is typed are auto-approved.

7.23 MUST: All N tools MUST appear in the completedBatchStep passed to
     the LLM. Denied tools carry observation:
     "User denied this tool execution." with success=false.
     The LLM sees the full picture and decides next steps.

7.24 MUST: When a parallel batch HITL pause is sent over Telegram,
     the message MUST include an inline keyboard with one row per tool:
       [✅ github]  [🚫 github]
       [✅ manage_schedule]  [🚫 manage_schedule]
       [✅ Approve All]  [🚫 Deny All]
     Text-based commands remain supported for non-Telegram channels.

7.25 MUST: Inline keyboard callback data MUST follow the format:
       BATCH_APPROVE|<sessionId>|<toolName>
       BATCH_DENY|<sessionId>|<toolName>
     The sessionId in the callback ensures the correct session is
     targeted even if the user has multiple active sessions.
     Use pipe separator (`|`) to prevent collision with namespaced tools (e.g. `github:read`).


7.26 MUST: AWAITING_APPROVAL checkpoints older than
     arcclaw.hitl.timeout-hours (default: 24) MUST be auto-denied
     by the WorkspaceManager GC scheduler. The user MUST receive a
     Telegram notification that their approval window expired.

7.27 MUST: After each per-tool decision (not all decided yet),
     the original HITL keyboard message MUST be edited in-place
     to show only the remaining PENDING tools. Decided tools
     (APPROVED/DENIED) MUST be removed from the keyboard rows.
     The "Approve All / Deny All" row is always retained until
     all tools are decided.

7.28 MUST: All text sent via sendWithKeyboard() MUST be escaped
     for MarkdownV2 before ParseMode="MarkdownV2" is applied.
     The escaping MUST use TelegramMarkdownV2Renderer.esc()
     which already handles all 18 Telegram special characters.
     AgentResultFormatter.formatHitlMessage() MUST produce
     plain text output (no MarkdownV2 markup). Escaping is
     applied at the call site in send(), not inside sendWithKeyboard(),
     to prevent double-escaping of already-rendered MarkdownV2 text.

7.29 INVARIANT: A parallel batch checkpoint MUST transition to
     a RESUMING pseudo-status (stored as a transient flag, NOT
     a new DB column) atomically before resumeParallelBatch()
     is called. If the flag is already set, the second caller
     MUST return a no-op response. This prevents double-resume
     when two callback_query updates arrive concurrently.
     The flag lives in a ConcurrentHashMap in StatefulAgentStore
     and is cleared when the session terminates.

§7.30 INVARIANT: Within a parallel batch, tools whose autonomy level permits execution WITHOUT human approval (autonomy >= FULL) MUST be pre-executed before the HITL pause. Their results MUST be stored in AgentCheckpoint.preExecutedResults (TEXT, JSON) as toolName → ToolResponse. On resume, CheckpointResumeHandler MUST use pre-stored results instead of re-executing those tools.

§7.31 INVARIANT: A tool is considered "pre-executable" if and only if: (a) the current session's autonomy level is FULL_AUTONOMY, OR (b) the tool name appears in arcclaw.hitl.pre-executable-tools config list. Safe tools are never automatically inferred — they must be explicitly configured. This prevents accidental side effects from misclassification.

§7.32 MUST: The HITL pause message shown to the user MUST list ONLY the tools that still require approval. Pre-executed tools MUST NOT appear in the keyboard or the approval text. The Approve All / Deny All buttons operate only on the PENDING tools.

§7.33 INVARIANT: Approval delegation is scoped to a single session. An admin (user listed in arcclaw.hitl.delegate-approver-ids) may issue /approve or /deny for ANY active AWAITING_APPROVAL session by providing the sessionId: "/approve <sessionId>". The delegate action is audit-logged with both the admin userId and the original session owner's userId. Delegation MUST NOT bypass per-tool granularity — /approve <sessionId> <toolName> is also valid.

§7.34 MUST: Web UI tool approval MUST be per-tool granular. The REST HITL endpoint MUST accept toolName as a parameter (in the JSON request body via CheckpointApprovalRequest). Passing no toolName is valid only for bulk Approve All / Deny All operations.

§7.35 TelegramCallbackHandler MUST reject any callback whose sessionId maps to a checkpoint older than arcclaw.hitl.timeout-hours before routing to any reflex. This guard is independent of and complementary to the WorkspaceManager GC auto-deny scheduler (§7.26).

§7.36 MUST: The `ApprovalStateMachine` in `arcclaw-core` is the single source of truth for HITL state transitions. It MUST implement the `ApprovalGateway` SPI and enforce idempotency (ALREADY_DECIDED) at the core layer. Channels delegate callbacks directly to this gateway without local state checks.

---

## 8. Sandbox and security rules

### 8.1 Secret handling
- Secrets must not be stored as immutable Strings when a zeroable `char[]` or byte[] lifecycle is required.
- Clear sensitive material immediately after use where the implementation already expects this.

### 8.2 Vault session identity
- System-level vault operations must use the dedicated system session identifier constant.
- Do not inline magic literals for system-session behavior.

### 8.3 Encryption invariants
- Associated authenticated data must remain bound to secret identity/key name.
- Do not weaken AAD binding or replace misuse-resistant encryption choices casually.

### 8.4 Seeder ordering
- Startup observers depending on dev-seeded secrets must execute after the seeder priority window.

### 8.5 Tool execution safety
- Sandbox/protected tools must default to isolated execution unless explicitly documented otherwise.
- Opting out of isolation is exceptional and requires strong justification.

## 8.6 Sandbox tool exception contract

`SandboxExecTool.execute()` MUST follow this exception contract:
- `BudgetExceededException` — MUST propagate (not catch); `AgentStateMachineRunner` handles it.
- `ToolExecutionException`, `TimeoutException`, `SecurityException`, `IOException` — MUST catch
  and return `ToolResponse.failure(message)`. Never propagate to `ToolInvoker`.
- Output exceeding 8192 characters MUST be truncated at exactly 8192 with suffix
  `[output truncated at 8192 chars]`. Never include raw Java stack traces in tool output.


---

## 9. Performance and reliability rules

### 9.1 Regex/pattern compilation
- Reusable regex/pattern objects must be `static final`.
- Do not compile patterns per request, per message, or inside hot-path methods.

### 9.2 Time measurement
- Use `System.nanoTime()` for elapsed durations.
- Do not use wall-clock time for duration measurement.

### 9.3 Async persistence backpressure
- Mandatory persistence events must never be dropped.
- Telemetry may degrade under bounded overflow only where explicitly designed.
- Backpressure behavior must be observable via metrics/logging.

### 9.4 Logging
- Do not log secrets or unsafe raw payloads by default.
- Debug facilities that expose prompts, tokens, or raw content must remain explicitly gated.

---

## 10. Configuration rules

### 10.1 Profile coverage
- Config changes must be reviewed across all relevant profiles and test overrides.
- Do not update one profile block and assume the others are safe.

### 10.2 Test-profile stability
- Test-only configuration that disables hot-reload or optional runtime behaviors must remain explicit and intentional.

### 10.3 Path resolution
- OS-native binaries and dynamic extensions must be resolved using normalized absolute paths when runtime behavior depends on working directory.
- Do not rely on launch-directory-relative behavior for native libs.

## 10.4 Test profile skill reload

The test profile MUST explicitly set:
  arcclaw.skills.hot-reload: false

Omitting this causes `SkillRegistry` to watch the filesystem during tests, producing
flaky failures when skill YAML files are rewritten by concurrent Maven forks.


---

## 11. Test discipline

### 11.1 Protected endpoint tests
- Tests for protected endpoints must include valid auth headers when the purpose is to test business behavior.
- A 401 for missing auth is not a passing business test.

### 11.2 Health tests
- Health checks may need test alternatives when production dependencies are intentionally absent in CI/test environments.
- Use CDI alternatives intentionally, not hidden environment dependencies.

### 11.3 Architecture-sensitive tests
The following areas require regression tests whenever touched:
- channel plugin discovery
- LLM provider SPI resolution
- workspace/session binding
- checkpoint resume/deny lifecycle
- SQLite vec fallback and path resolution
- MCP framing behavior
- native-sensitive template/reflection behavior
- sandbox circuit-breaker behavior

---

## 12. Legacy traps to avoid

- Do not copy framework workaround comments into new code without proving the workaround is still necessary.
- Do not add new stringly-typed conventions when a canonical accessor/type already exists.
- Do not solve architecture drift by adding more markdown-only warnings.
- Do not treat “works in dev mode” as proof of correctness for native mode, test mode, or cross-module execution.
- Do not introduce adapter-specific behavior into core just because Telegram is currently the busiest channel.

---

## 13. When this file must change

Update this file when:
- a real invariant is added or removed,
- an old workaround becomes obsolete,
- an invariant is moved from documentation into code/test/build enforcement,
- module boundaries or lifecycle rules materially change.

Do not update this file for:
- minor code style,
- one-off incidents already covered by tests,
- temporary experiments.

---

## 14. Enforcement backlog

The long-term goal is to shrink this document.

Every time an invariant here can be enforced by:
- startup validator,
- build step,
- architecture test,
- CDI decorator/interceptor,
- dedicated helper API,
- typed wrapper/value object,
- failing integration test,

---

## 15. Module CDI discovery contract

Every non-app, non-deployment Maven module that contains CDI beans MUST:
1. Include `jandex-maven-plugin` in its `pom.xml` build section.
2. Include `src/main/resources/META-INF/beans.xml` with `bean-discovery-mode="annotated"`.

Without Jandex, `@ApplicationScoped` beans in library modules are **silently invisible**
to the Quarkus CDI container — no error, no warning, just missing beans.
This applies to every `arcclaw-channel-*` module added in P67 and beyond.

---

## 16. New Module Creation

### 16.1 Dependency Cycle Prevention
- NEW modules MUST NOT depend on `arcclaw-app`. 
- `arcclaw-app` is the "leaf" of the dependency tree for runtime features.
- If a new module needs logic from `arcclaw-app` (e.g., `LlmModelFactory`), that logic MUST be extracted into an SPI/Gateway in `arcclaw-core`.
- The new module depends only on the SPI interface; `arcclaw-app` provides the `@ApplicationScoped` implementation.

### 16.2 Test Configuration Boilerplate
- EVERY new module MUST include `src/test/resources/application.yaml`.
- The test configuration MUST provide dummy values for core properties required by scanned beans:
  - `arcclaw.vault.pepper`: "mock-pepper"
  - `quarkus.langchain4j.openai.timeout`: "30s"
  - `arcclaw.data-dir`: "target/data"
- Standardize the `hibernate-orm.packages` list to include `com.arcclaw.core.*` entity packages to avoid `UnsatisfiedDependency` for `EntityManager`.

### 16.3 CDI Discovery (Jandex)
- Refer to §15. Every module MUST have `jandex-maven-plugin` and `beans.xml`.
- Without Jandex, internal beans will be **silently ignored** when the module is consumed.

### 16.4 Session Management (§16.4 INVARIANT)
- ArcClaw primarily uses RESTEasy Reactive (`quarkus-rest`).
- Since RESTEasy Reactive does NOT support CDI `@SessionScoped` by default (due to bypassing the Servlet layer), wizard state (like onboarding) MUST use `@ApplicationScoped` if session-isolated state is not strictly required by the multi-user runtime model.
- If true session isolation is required in a Reactive module, implement a manual session registry keyed by a `UUID` stored in a secure Cookie.

### 16.5 Core Mocking in Tests
- Tests that trigger bean-scanning of core handlers MUST satisfy their dependencies via `@ApplicationScoped` mocks in `src/test/java`:
  - `IntentClassifier` (satisfies `IntentClassificationHandler`)
  - `AgentChatModelProvider` (satisfies `ChatModel` injections)
  - `ChatModel` with `@Identifier("compaction")` (satisfies `CompactionService`)

### 16.6 JVM Shutdown Hang in Tests (§16.6 INVARIANT)
- Some Quarkus modules (especially those with `arcclaw-mcp` or `AsyncPersistenceEngine`) may hang for 30+ seconds during JVM shutdown in tests because of non-daemon background threads.
- DO NOT rely on default Surefire timeouts. EVERY new module MUST configure the `maven-surefire-plugin` with:
  - `<forkCount>1</forkCount>` (Avoid forkCount=0 due to Quarkus classloader conflicts)
  - `<reuseForks>true</reuseForks>`
  - `<forkedProcessExitTimeoutInSeconds>10</forkedProcessExitTimeoutInSeconds>` (Forces a hard kill after 10s if graceful shutdown fails)
- Additionally, `src/test/resources/application.yaml` MUST include:
  - `quarkus.transaction-manager.enable-recovery: false`
  - `quarkiverse.mcp.server.http.enabled: false`
  - `quarkiverse.mcp.server.stdio.enabled: false`
  - `quarkus.shutdown.timeout: 5S`

### 16.7 App Indexing contract
- For any new module providing CDI beans consumed by the main application, the module MUST be added to the `quarkus.index-dependency` section of `arcclaw-app/src/main/resources/application.yaml`.
- Failure to do so may trigger `jakarta.enterprise.inject.UnsatisfiedResolutionException` for core beans (e.g. `DevSecretSeeder`) during the application's build validation phase, as the container boundary for bean discovery becomes inconsistent.

### 16.8 Assertion and Assumption Standards
- New modules MUST use `org.junit.jupiter.api.Assertions` and `org.junit.jupiter.api.Assumptions` for all tests.
- **AssertJ is NOT used** in this project. Do not import or use `assertThat`.
- This ensures consistency and reduces classpath bloat in the final native binary.

### 16.9 beans.xml Schema Mapping
- New modules MUST use the Jakarta EE 10 (version 4.0) schema for `src/main/resources/META-INF/beans.xml` to ensure consistency with `arcclaw-core`.
- Example header: `<beans xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0" bean-discovery-mode="annotated">`

### 16.10 ToolResponse API
- `ToolResponse` is a Java **Record**. Use canonical accessors:
  - `success()` (instead of `isSuccess()`)
  - `exitCode()` (instead of `status()`)
  - `output()` (instead of `content()`)

### 16.11 Bootstrap and Setup Integration (§16.11 INVARIANT)
- New modules with required or optional user settings (API keys, toggles, binary paths) MUST be integrated into the interactive setup wizard in `BinaryBootstrap`.
- Update `com.arcclaw.app.util.CliUtils.getMissingVarSpecs()` in `arcclaw-app` to include the `VarSpec` for these variables.
- If the module requires binary dependencies (e.g., Chromium, specialized tools), implement an idempotent `*Installer` utility that:
  - Uses `ProcessBuilder.inheritIO()` to stream progress to the user's terminal.
  - Prefers `mvnw exec:java` if in a source tree, falling back to `java -cp` for binary distributions.
  - Detects existing binaries to avoid redundant downloads.
- **Interactive UI:** All bootstrap prompts MUST use `readLineWithFallback(System.console(), prompt)` to support environment-redirected terminals (e.g., `mise`, `podman exec`).
- **Dependency Constraint:** Per §16.1, the new module MUST NOT depend on `arcclaw-app`. The integration logic is owned by `arcclaw-app` in `BinaryBootstrap`.


## 17. General Java Guards

- Null-check `e.getMessage()` before use — several JVM exception types return `null`.
  Use: `e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()`. Prefer `String.format(...)` / `log.infof(...)` over concatenation in log statements. `MDC` values removed in `finally` blocks only — never remove in `catch`.

## 18. REST Status Code Conventions

| Operation | Endpoint pattern | Expected status |
|---|---|---|
| Create session | `POST /api/v1/sessions` | `201` |
| Run agent (sync) | `POST /api/v1/sessions/{id}/run` | `200` |
| Approve | `POST /api/v1/sessions/{id}/approve` | `200` |
| Onboarding Status | `GET /onboarding/status` | `200` |
| Advance Wizard | `POST /onboarding/step/{N}` | `200` |
| Session not found | any session endpoint | `404` |
| Cross-key access | any session endpoint | `401` + `code=SESSION_ACCESS_DENIED` |
| Terminate session (owner)   | `DELETE /api/v1/sessions/{id}` | `204` |
| Terminate session (foreign) | `DELETE /api/v1/sessions/{id}` | `401` + `code=SESSION_ACCESS_DENIED` |

---

## 19. Validation Checklist (P84+)

Run this checklist mentally before every major refactor touching persistence, approval, scheduling, or the agent loop:

- [ ] Does any new `@Transactional` method call another `@Transactional(REQUIRES_NEW)` method? (§5.8)
- [ ] Does any new method run an LLM call or external HTTP request inside a `@Transactional`? (§5.9)
- [ ] Is `CheckpointApprovalService` still annotated `NOT_SUPPORTED` on all 4 mutating methods? (§5.10)
- [ ] Is `ApprovalStateMachine.decide()` still NOT_SUPPORTED? (§5.11)
- [ ] Is `AgentCronScheduler.pollAndDispatch()` still NOT_SUPPORTED with `reclaimStaleCrons` isolated? (§5.12)
- [ ] Does Agroal config still have `background-validation=true` and `max-size≥3`? (§5.13)
- [ ] Is `SqliteRetryTemplate` only called from a NOT_SUPPORTED or un-annotated outer frame? (§5.14)
- [ ] Does any new `CheckpointStatus` terminal value need to be added to `doMarkCheckpointsFailedRequiresNew`? (§5.18)
- [ ] Does any new checkpoint query include `ORDER BY c.id DESC`? (§5.19)
- [ ] Does `AsyncPersistenceEngine` still bind `WorkspaceScopeHolder` per event, not per thread? (§5.17)
---

## 20. Routing and Classification Consolidation

### 20.1 Implementation Proximity
- Interface implementations for core SPIs (e.g. IntentClassifier) SHOULD reside in the same module as their primary injection points (e.g. IntentClassificationHandler in arcclaw-core).
- **Rationale:** Placing the only implementation of a required core bean in a downstream module (like arcclaw-app) frequently triggers jakarta.enterprise.inject.UnsatisfiedResolutionException during the validation phase of the upstream module's build, especially in native-image compilation.
- Consolidated IntentClassifierService, SkillAwareIntentClassifier, and their associated LlmModelFactory into arcclaw-core to ensure reliable CDI resolution and build stability.

### 20.2 LLM Provider Dependencies
- When moving routing logic into arcclaw-core, all required LLM provider extensions (e.g. quarkus-langchain4j-anthropic) MUST also be added to arcclaw-core.
- Failure to do so leads to ClassNotFoundException or unresolved imports in the routing factories (e.g. ClaudeCodeProviderFactory).

---

## 21. UI Architecture Invariants

### 21.1 Technology Stack
- MUST use **Lit** (Web Components) for UI structure and logic.
- MUST use **Vite** as the build tool and dev server.
- MUST use **TypeScript** in `strict` mode for all frontend code.

### 21.2 Component Communication & State
- MUST use **`@lit/context`** for sharing application-wide state (e.g., `arcclawSessionId`, user profile) between nested components.
- Direct `window` object state-sharing or global mutable stores SHOULD be avoided in favor of the context provider pattern.

### 21.3 Security & Sanitization
- Every dynamic HTML fragment rendered from LLM or agent output MUST be sanitized with **`dompurify`** before insertion into the DOM.
- Markdown rendering (via `marked`) MUST be piped through the sanitizer to prevent XSS from unsafe agent outputs.

### 21.4 Backend Communication
- All data-plane interaction MUST flow through the **REST API** (`/api/v1`) or **WebSockets (`/ws/chat/{sessionId}`)**.
- The UI MUST handle standard error codes (401 Unauthorized, 404 Not Found, 500 Server Error) gracefully, providing clear feedback to the user.

### 21.5 Styling & Aesthetics
- MUST use **Vanilla CSS** with CSS Custom Properties (Variables) for theming and layout.
- MUST NOT introduce heavy utility frameworks (Tailwind, Bootstrap) unless explicitly requested and approved as a project-wide standard.
- Aim for a premium, responsive design with smooth transitions and micro-animations.

### 21.6 Packaging & Deployment
- The UI build process MUST output to `arcclaw-ui/dist/`.
- The `mise build:ui-copy` task is the authoritative mechanism for moving the bundled UI into the `arcclaw-app` `META-INF/resources` directory for production packaging.
- Do NOT commit the `dist/` folder to version control.
