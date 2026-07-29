# Amsha Agent Architecture Invariants

## Purpose

This document defines the architecture rules that MUST hold across Amsha Agent.
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

Refer to the [Module Architecture Diagram](assets/amsha_module_architecture.svg) for a visual layout of the components and their interactions.

### 1.1 Deployment module
- `amsha-deployment` is build-time only.
- MUST NOT place runtime business logic, request handling, persistence, tool logic, or channel behavior there.
- Build steps may generate metadata/resources only.

### 1.2 Core module
- `amsha-core` owns agent runtime, workspace/session lifecycle, governance, persistence, skills, structured output, reflexes, audit, sandbox contracts, and generic pipeline behavior.
- `amsha-core` MUST NOT depend on Telegram-specific behavior or any single channel assumption.
- **Persistence boundary:** `amsha-core` MUST NOT contain JDBI `Handle`/`Jdbi` references or raw SQL strings. All database access MUST be delegated to pure Port interfaces defined in `amsha-persistence-api`. (Enforced by `ArchitectureBoundaryTest`).

### 1.3 App module
- `amsha-app` owns application wiring, REST resources, startup bootstrap, config producers, and current adapter registration.
- App-layer code SHOULD stay thin.
- Business rules belong in core unless they are truly transport/framework specific.

### 1.4 Channel abstraction
- Channel-neutral flows MUST NOT default missing channel metadata to a specific channel implementation.
- New channels must fit the channel SPI without changing core engine behavior.
- Any fallback to Telegram is LEGACY and should be removed, not copied.
- **P82-85 SPI Enforcement:** Channel modules (e.g., `amsha-channel-telegram`, `amsha-channel-web`) MUST NOT import `com.amsha.core.*` directly. 
  - Interaction with core HITL state MUST flow through the `com.amsha.channel.api.hitl.ApprovalGateway` SPI.
  - Querying pending HITL approval state MUST flow through the `com.amsha.channel.api.hitl.PendingApprovalChecker` SPI.
  - Identity validation (API keys) MUST flow through `com.amsha.channel.api.ChannelServices.isValidIdentity()`. 
  - Direct injection of core classes like `com.amsha.core.security.SessionOwnerResolver` or `com.amsha.core.state.StatefulAgentStore` in channel modules is FORBIDDEN as it violates architectural boundaries and fails CDI resolution in module-level tests.


### 1.5 Channel type policy
- POLICY: WorkspaceContext.channelType() returns Optional<String>. Use requireChannelType() only at call sites where a missing channelType is a fatal invariant violation. Use channelType().orElse("UNKNOWN") at call sites where a fallback is acceptable (e.g., audit logging only).

---

## 2. Context and session binding

### 2.1 Per-session state
- MUST use scoped/session-aware context primitives for per-session data.
- MUST NOT store per-session mutable state in `@ApplicationScoped` fields.

### 2.2 Two context layers
Amsha Agent has two distinct binding layers:

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
- Async work that needs workspace context MUST be submitted through `ContextAwareExecutorService` (or explicitly wrapped with `WorkspaceScopeHolder.wrap()`).
- The wrapper captures and re-binds the active session, user, chat, autonomy, agent role, channel type, and channel platform ID when they are bound. Do not bypass it for workspace-aware work.
- Missing this binding can lose identity, breaking role-based access control, tool governance, and audit logging for background work.

> **`AsyncPersistenceEngine` exception:** The drain threads of `AsyncPersistenceEngine`
> are exempt from the full `WorkspaceContext.run(...)` requirement because they
> process events from many sessions simultaneously. They bind only `WorkspaceScopeHolder`
> per event (session scope), not the full workspace context. This is intentional and is
> the only legitimate multi-session background binding pattern. See §5.17.

### 2.8 Fallback session ID derivation

`FallbackSessionIdResolver` MUST derive session IDs deterministically from the
three-tuple `(channelType, channelId, userId)`.

- The fallback key format is `{channelType}:{channelId}:{userId}`.
- Multi-channel sessions are isolated by `channelType` — a Telegram user and a
  WebSocket user with the same numeric ID MUST NOT share a session.
- Cron sessions (`sessionId.startsWith("cron-")`) MUST be excluded from chat
  approval routing to prevent cron checkpoints from appearing in user-facing
  approval queues.

---

## 3. Runtime execution invariants

### 3.1 Agent loop boundaries
- `AgentLoop` orchestration must remain distinct from tool execution, checkpoint persistence, and message transport (see the [Agent Loop Diagram](assets/amsha_agent_loop.svg) for visual context).
- Do not move transport-specific logic into the core state machine.

### 3.2 Tool invocation path
- All tool execution MUST flow through `ToolRegistry` and `ToolInvoker` (see the [Tool Governance & Sandbox Execution Flow Diagram](assets/amsha_tool_governance.svg) for visual context).
- Do not bypass guard, audit, sanitization, or approval logic by calling tool implementation classes directly.

### 3.2.1 Tool execution-category routing

- `ToolCategoryMapper` is the single resolver from a tool name to the
  category-specific executor bulkhead. Do not add a second tool-name-to-executor
  mapping in a transport, tool adapter, or coordinator.
- Registered tools may declare a non-`INTERNAL` category through
  `AgentTool.category()` or inherited `@ToolMetadata.category()`. The explicit
  `AgentTool` result takes precedence; `ToolCapability.category()` remains the
  compatibility source for built-in tools.
- Discovered MCP tools carry their category on `DynamicToolCapability`. They
  default to `NETWORK`; `register_mcp_server` may assign `NETWORK`, `COMPUTE`,
  `DATABASE`, or `INTERNAL` for all tools discovered from that server.
- `ParallelToolStepExecutor` MUST select its `ManagedExecutor` through
  `ToolCategoryMapper`. Sequential execution is not currently category-bulkheaded;
  do not describe this mechanism as applying to every sequential tool call until
  that path is explicitly changed and verified.

### 3.3 Tool observation sanitization
- Every tool response MUST be sanitized before history append.
- No tool type is exempt, including GitHub, MCP, sandbox, or internal tools.

### 3.4 Approval handling
- Approval-required executions MUST persist checkpoint state before exiting the loop.
- Resume/deny behavior must use the checkpoint lifecycle, not ad hoc retry logic.
- The double-resume guard (`StatefulAgentStore.claimForResume()` backed by `ResumeCoordinationGuard.markResuming()`) MUST remain in
  place. It prevents two concurrent approval signals (e.g., Telegram callback + REST)
  from both triggering `AgentLoop.run()` simultaneously for the same session.
  `clearBatchResuming()` MUST be called in the `finally` block of the resume path and
  by `WorkspaceManager.terminateSession()`.
- MUST NOT call `resumeCheckpoint()` / `AgentLoop.run(isResumeSignal=true)` for 
  denial decisions. Denial is a termination, not a resume. A dedicated 
  `rejectAndTerminate()` path MUST mark the checkpoint FAILED, notify the user 
  via channel, and call `WorkspaceManager.terminateSession()` without entering 
  the agent loop.
- `HitlService.resolve()` delegates to `CheckpointApprovalService.approveTool()`/`denyTool()`/`approveAll()`/`denyAll()` followed by `checkpointApprovalService.triggerResume(resolvedCp)`. The decision methods update batch decisions and persist; `triggerResume()` enters the transaction-free zone.

### 3.5 Structured retry budget behavior
- Soft budget warnings may be logged and tolerated for retry behavior.
- Hard budget exceedance must propagate upward and stop execution.
- Do not swallow hard budget failures.
- SelfCorrectionRetryManager MUST NOT catch BudgetExceededException or BudgetWarningException.
  These propagate unchanged to the AgentLoop catch block.
  llmSchemaErrorCount MUST remain a plain int - never AtomicInteger - to prevent
  the §3.7 CAS-before-check ordering violation from being introducible.

## 3.6 Chat memory storage boundary

System-role messages (injected tool rules, persona definitions, sanitized tool output headers)
MUST NOT be stored in the SQLite `ChatMemoryStore`. Only user and assistant turns are persisted.
Storing system messages inflates context, corrupts compaction logic, and leaks tool contracts.

---

## 3.7 Budget threshold check ordering

In `StructuredRetryLoop`, `assertWithinBudget()` threshold check MUST precede `compareAndSet()`.
If CAS fires first at 79% budget, `warningSent` is permanently `true` - the real 80% boundary
warning is forever suppressed. The ordering is: check → log → CAS, never CAS → check.

## 3.8 Strict terminal finish rules

The coordinator persona MUST emit a `FINISH` action immediately after any successful
tool execution. No additional tool calls may follow a `FINISH` in the same turn.

- `AgentStateMachineRunner` enforces a two-pass protocol: pass 1 sends tool results
  with a terminal-mode hint; pass 2 sends a plain-text summary guidance.
- The `finally` block in the finish handler MUST clean up history to prevent
  residual system messages from corrupting subsequent turns.
- Violation: Tool chaining after `FINISH` corrupts the state machine and produces
  orphaned checkpoint states.

## 3.9 Global budget halt

`WorkspaceManager.haltSession()` provides a circuit-breaker for global budget
exceedance with a 5-minute TTL.

- `PreflightState` MUST check the global iteration budget before each loop iteration.
- If the budget is exceeded, `haltSession(sessionId)` marks the session as halted.
- Halted sessions reject new tool invocations until the TTL expires or an operator
  explicitly unhalts the session.
- MUST NOT allow tool execution while the session is in `HALTED` state.

## 3.10 Telegram HITL notification flush

Channel callback handlers that bypass `AbstractDecisionReflex` (e.g., Telegram
inline keyboard approval callbacks) MUST call `flushPendingNotifications()`
after processing approval decisions.

- `TelegramCallbackHandler` injects `ApprovalNotificationService` and calls
  `flushPendingNotifications()` in the callback path.
- Without this flush, approval notifications may be silently dropped, leaving
  the user without feedback after a HITL decision.

## 3.11 GraalVM SecureRandom local instantiation

Native-image builds MUST NOT hold `SecureRandom` in `static` or `static final` fields.

- `SecureRandom` instances MUST be created locally per use with `new SecureRandom()`.
- The byte array returned by `SecureRandom` MUST be zeroed in a `finally` block
  after use (defense-in-depth against heap inspection).
- Static fields cause `UnsupportedFeatureException` at native-image build time
  because GraalVM attempts to snapshot the object into the image heap.

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
- `SkillAwareIntentDetector` MUST check `SkillRegistry` before LLM fallback.
- Do not invert that order.

### 4.4 Role naming
- Internal role identity is the role name, not deity display text.
- Alias handling belongs in parsing/resolution code, not scattered across business logic.
- Template selection over roles must remain exhaustive.
- **P82 Data-Driven Personas:** Agent personas are collapsed into the `DefaultAgent` implementation. System prompts are loaded from the multi-backend `PromptRegistry` (SQLite/PostgreSQL/Filesystem), which auto-seeds from `src/main/resources/templates/` on first startup and supports runtime edits via `PUT /admin/prompts/{id}`. Class-per-persona inheritance is FORBIDDEN.


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

## 4.9 Prompt compact-mode parity (§Invariant-PromptCompactParity)

The system prompt builder supports a compact path controlled by
`amsha.llm.compact-prompt-enabled`. Compact prompts MUST remain behaviorally
equivalent to full prompts for every load-bearing rule.

Invariants:

- When a full prompt section is changed, the compact counterpart MUST be updated
  in the same change. Never let compact mode silently drop a rule.
- Critical rules that MUST be present in compact mode:
  1. **Termination**: After a successful tool observation, the agent MUST emit
     a `FINISH` action with a concise plain-text final answer. It MUST NOT plan
     another step or leave the turn open.
  2. **Skill self-execution**: Dynamic `skill:*` roles MUST execute the task
     themselves using only their `PERMITTED TOOLS`. They MUST NOT delegate
     execution to `brahma`, `shiva`, or another persona.
  3. **Execution boundary**: The choice of `host_exec` vs `sandbox_exec` MUST
     match the role's capabilities as resolved by `RoleRegistry`.
- If a new persona or role template gains a compact variant, both variants MUST
  be kept in sync.
- Verification: after any prompt change, run
  `./mvnw test -pl core -Dtest="SystemPromptBuilderTest"`.

## 4.10 AgentFunctionProcessor schema source

`AgentFunctionProcessor` MUST generate JSON schema from the SAM **return type** using
`SchemaFactoryWrapper.acceptJsonFormatVisitor(returnTypeClass, visitor)`.
MUST NOT use `ToolSpecifications.toolSpecificationFrom()` - that generates the INPUT
parameter schema, not the output schema, and produces silent wrong behavior in native tools.

## 4.10 Delegation Capability Guard

`DelegateToAgentTool` MUST validate that the target role's capabilities are a subset of the delegating role's capabilities before constructing the sub-agent loop. Delegation that would grant the sub-agent capabilities the parent role does not have MUST be rejected with a descriptive tool observation. Transitive capability escalation via delegation chains is FORBIDDEN.

### 4.10.1 Sub-Session Governance Level (§Invariant-Sub-Governance)
Sub-sessions spawned by `DelegateToAgentTool` MUST inherit `READONLY` from the parent if the parent is `READONLY`. `SUPERVISED` and `FULL` both resolve to `FULL` to prevent unresumable synchronous HITL checkpoints inside the delegation chain. Max delegation depth is governed by `amsha.delegation.max-depth` (default 2).

- The scheduling tools (`schedule-cron`, `delete-cron`, `list-crons`) are EXEMPT from
  HITL approval checks regardless of the delegating role's autonomy level, because
  scheduling is metadata-only and does not execute arbitrary tool actions.
  This exemption is encoded in `ToolInvocationGuard` and MUST NOT be removed when
  adding new scheduling-adjacent tools without explicit review.

### 4.10.2 Role-Based Tool Governance Bypass (§Invariant-RoleGovernanceBypass)

`ToolGovernanceGuard` has a **role trust pre-flight check** that runs before operation-level
policy enforcement. Trusted roles (those with `requiresApproval=false` in their
`RoleDefinition`) bypass operation policy checks for **non-sensitive** operations.

**Sensitive operations (`NETWORK_OUTBOUND` and `EXECUTE_SHELL`) always require HITL**
regardless of role trust:

```java
boolean isSensitiveOp =
    requiredOp == ToolOperation.NETWORK_OUTBOUND || requiredOp == ToolOperation.EXECUTE_SHELL;
if (isTrustedRole && !isSensitiveOp) {
    return; // trusted role — allow non-sensitive operations
}
```

MUST NOT add new `ToolOperation` values without evaluating whether they should be
classified as sensitive. Any operation involving external network access, arbitrary
code execution, or privilege escalation MUST be added to the `isSensitiveOp` check.

MUST NOT add roles to the HITL-exempt tools list (`hitl-exempt-tools`) as a replacement
for the role trust pre-flight. The role trust check is preferred because it is
dynamic (evaluated against the `RoleRegistry` at runtime) rather than static
(config-driven). The `garuda` role has been removed from `hitl-exempt-tools` —
its trusted role status is determined by `RoleRegistry.requiresApprovalForTools()`.

**Reference:** `ToolGovernanceGuard.verifyAutonomy()`, `AgentRoleEnum.RoleDefinition.requiresApproval`, `PolicyConfig.HITL_EXEMPT_TOOLS`.

### 4.10.3 Group-Aware Escalation Order (§Invariant-GroupEscalation)

Agent groups (Lokas) route tasks to group leads who decide: self-handle, consult family, or escalate out.
Escalation priority order is **sacred** — never reorder:
1. Role-specific rules (`escalateByRole`) — highest priority
2. New persona rules (`determineNewPersonaEscalation`)
3. Group-aware fallback (`tryIntraGroupEscalation`) — lowest priority
4. Sandbox code error fallback (→ Shiva)

Groups are **in-memory only** — no schema changes needed for SQLite/PG/FS. `AgentGroupRegistry` holds built-in groups; dynamic groups registered at runtime via `registerDynamic()`.

Escalation keywords MUST be specific enough to avoid false positives. Single words like `"fix"`, `"broken"`, `"patch"` are too broad — use `"fix bug"`, `"broken code"`, `"patch bug"`. Tool parameter mode values (e.g., `"group_consult"`, `"solo"`) MUST be `static final String` constants in the tool class.

All role ID returns from escalation/routing methods MUST use `AgentRoleEnum.fromString(id).toScopedName()` (or its canonical form) instead of raw strings to ensure lowercase canonical forms are used. `AgentRoleEnum.fromString()` MUST be wrapped in a try-catch block when dealing with potentially unknown dynamic or skill role IDs, falling back to raw string and logging a warning.

### 4.10.4 Record Cross-Field Validation (§Invariant-RecordValidation)

Records with inter-field constraints MUST validate in the compact constructor (e.g., `AgentGroup` validates that `leadRoleId` is in `memberRoleIds`). Static data populated once and never modified MUST use `Map.copyOf()` — NOT `ConcurrentHashMap`.

## 4.11 Intent Routing Negation Safety

Fast-path routing patterns in IntentRoutingProvider implementations
MUST NOT match negations of the intent they detect (e.g. 'do NOT
schedule' must not route to coordinator as a scheduling request).
If a pattern is broad enough to match negations, implement
IntentRoutingProvider.shouldSkip(String userMessage) with a
static final Pattern negation guard. Never compile patterns
inline - use static final Pattern per §9.1.

## 4.12 Agent-Loop Guardrails (§Invariant-AgentLoopGuards)

The agent loop has three cooperative guards that MUST remain in place (see `AGENTS.md` §1.18):

- **`PreflightState`** MUST check the global iteration budget (`WorkspaceContext.incrementAndCheckGlobalIterations()`) before each loop iteration; exceeding it terminates the session as halted.
- **`LoopStuckDetector`** MUST inspect recent reasoning history and inject a corrective hint when the agent repeats non-progressing tool calls (threshold: 2 identical repeats, or 3 read-only-only repeats). Hints are derived from the actual repeated tool input — do NOT hardcode skill-specific paths.
- **`ToolInvocationGuard`** is the pre-invocation interceptor chain; it dedups identical repeated tool calls and evicts stale observations. It MUST run before any tool executes. Do not bypass it to "speed up" execution.

## 4.13 HTML Artifact Collapsing (§Invariant-HtmlArtifactCollapse)

`HtmlArtifactTransformer` (`ObservationTransformer`, priority 40) MUST collapse generated HTML artifacts from `host_exec`/`sandbox_exec` output into a short browser-preview notice via `HtmlArtifactUtil.collapseHtmlArtifact()`. The HTML is mirrored to a browser-preview target rather than echoed into chat. This transformer MUST NOT be disabled — raw HTML in the conversation floods the context window and breaks the FINISH flow. See `AGENTS.md` §1.19.

## 4.14 Skill Role `host_exec` Gating (§Invariant-SkillHostExecGating)

`host_exec` for skill roles is granted ONLY through `HostExecSkillGrant`, which reads `amsha.tools.host-exec.skills` (default `jdb-debugger`). `SkillLoader` defaults MUST NOT be widened and `host_exec` MUST NOT be granted to unlisted skills directly. `EXECUTE_SHELL` HITL remains enforced by `ToolGovernanceGuard` after the grant. See `AGENTS.md` §1.20.

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
- Core repositories MUST NOT use JDBI `Handle`/`Jdbi` directly (§1.2).
- All database operations in `amsha-core` MUST flow through Port interfaces defined in `amsha-persistence-api`.
- Implementations (Adapters) reside in specialized modules (e.g. `amsha-persistence-sqlite`, `amsha-persistence-postgres`).

### 5.4 JDBI Transaction Behavior (SQLite)
- JDBI manages its own connections and transactions — do NOT wrap JDBI operations in Jakarta `@Transactional`. Let JDBI handle transaction boundaries via `jdbi.useTransaction()` / `jdbi.inTransaction()`.
- The adapters in `amsha-persistence-sqlite` use `jdbi.useTransaction()` for write operations and obtain handles via `jdbi.withExtension()` / `jdbi.withHandle()`.
- `@Transactional` annotations in `amsha-core` beans (e.g., `AuditEventRecorder`) remain for JTA compliance but are effectively no-ops for JDBI-backed stores — the actual database write is managed by JDBI's own handle lifecycle.

### 5.5 Read visibility in tests
- Test writes must be committed before REST assertions that depend on them.
- Snapshot isolation assumptions in SQLite tests are easy to get wrong; prefer explicit cleanup and committed setup.

### 5.6 Identity / schema (JDBI + Flyway — Hibernate removed)
- All schemas (SQLite and PostgreSQL) are managed via Flyway migrations in their respective adapter modules.
- The custom Hibernate SQLite dialect (`AmshaSQLiteDialect`) and all JPA entity classes have been removed.
- Identity columns use `INTEGER PRIMARY KEY AUTOINCREMENT` (SQLite) or `BIGSERIAL` (PostgreSQL) in DDL.
- `@Version` optimistic locking is not used — JDBI-based adapters manage concurrency via explicit SQL conditions (e.g., `WHERE status = :expectedStatus` in status transitions).

#### Identity membership concurrency

- Removing a membership must use the atomic `deleteMembershipIfNotLastOwner` port operation, not a separate owner count followed by a delete.
- SQLite performs the conditional delete in one statement; PostgreSQL serializes the owner check with an advisory lock; the filesystem adapter synchronizes the check and delete.
- Every implementation must preserve the invariant that concurrent requests cannot remove the final active `OWNER` from a workspace.

## 5.7 EncryptedSecret version field ownership (REMOVED — JDBI Migration)

`EncryptedSecret.ormVersion` was Hibernate-managed and is now **removed**. Both `SqliteSecretStoreAdapter` and `PostgresSecretStoreAdapter` use `SecretJdbiDao` directly via the `SecretDtoRow` record, bypassing all JPA entity classes. The `ormVersion` column no longer exists in the schema — both backends use JDBI exclusively.

---

## 5.8 SQLite Single-Writer Constraint (CRITICAL - ROOT CAUSE OF P84 BUSY CASCADE)

SQLite WAL mode allows any number of concurrent readers but **only one writer at a time**.
This is a database engine constraint, not a configuration choice.

**The Correct Transaction Strategy for SQLite:**

1. **General Data Persistence (REQUIRED)**: 
   - Most methods in `CheckpointStorePort` and `CheckpointStatusService` MUST use `@Transactional(REQUIRED)`. 
   - This allows multiple service calls on the same thread to join a single transaction and share the same database connection/write lock. 
   - Using `REQUIRES_NEW` for general data in a nested call chain causes self-deadlock because the outer transaction holds the write lock while the inner transaction waits for it.

2. **Advisory Locks (REQUIRES_NEW)**:
   - See §5.8.1 for the mandatory isolation exception.

**The forbidden nesting pattern (Connection Exhaustion / Deadlock):**
```
@Transactional(REQUIRED)               ← acquires Connection-1, starts XA BEGIN IMMEDIATE
  └─ calls store.save()
       └─ @Transactional(REQUIRES_NEW)  ← acquires Connection-2, tries to write
            └─ SQLITE_BUSY - Connection-1 holds the reserved write lock
```

MUST NOT: Annotate any method `@Transactional(REQUIRED)` (or bare `@Transactional`) if that method directly or transitively calls any `@Transactional(REQUIRES_NEW)` method that writes to the SAME database.

MUST NOT: Use JPA `EntityManager` operations (`em.merge()`, `em.persist()`, `em.find()`) in any module — Hibernate has been removed. All writes use JDBI SQL statements or DAO methods.

### 5.8.1 Advisory Lock Isolation Exception (§T5-3)

`SqliteAdvisoryLockAdapter` (`doTryLock`, `doUnlock`) MUST remain `@Transactional(REQUIRES_NEW)`. 

**Rationale:** 
- Advisory locks are used for cross-process/cross-thread coordination (e.g., leader election).
- The lock acquisition MUST commit immediately and be visible to other threads/processes. 
- If it joined a `REQUIRED` transaction, the lock would not be "taken" in the DB until the entire outer transaction committed, allowing other threads to race and enter the same critical section.

**Safety Condition:**
- Callers of advisory locks (e.g., `AgentCronScheduler`, `CheckpointResumeHandler`, `TelegramCallbackHandler`) MUST be non-transactional (`NOT_SUPPORTED`) at their entry points.
- This ensures the `REQUIRES_NEW` lock transaction is the *only* transaction on the thread, preventing the §5.8 nesting deadlock.

---

## 5.9 Transaction-Free Zone: Long-Running Operations

---

## 5.9 Transaction-Free Zone: Long-Running Operations

Any operation that performs slow I/O (LLM network calls, external REST, arbitrary sleep,
full agent loops) MUST NOT run inside an active JTA transaction.

The following are **transaction-free zones** - no `@Transactional` annotation may exist
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
for the entire duration. Every concurrent write - audit drain, approval, cron scheduler -
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
| `approve()` | `@Transactional(NOT_SUPPORTED)` | Per-session approval (non-per-tool path) |
| `deny()` | `@Transactional(NOT_SUPPORTED)` | Per-session denial (non-per-tool path) |
| `triggerResume()` | None / not annotated | In the transaction-free zone (§5.9) |
| `ApprovalCheckpointManager.persistBatchWithPreExecuted()` | Via `agentStore.save()` (REQUIRES_NEW) | Inherits REQUIRES_NEW from store |
| `HitlService.resolve()` | `@Transactional(NOT_SUPPORTED)` | App-layer entry point for UI approval; delegates to CheckpointApprovalService methods which are already NOT_SUPPORTED |

MUST NOT change any of these to `@Transactional` or `@Transactional(REQUIRED)`.
Doing so re-introduces the SQLITE_BUSY failure pattern described in §5.8.
This contract is non-negotiable and must be re-verified after every major refactor.

---

### 5.10.1 Multi-Backend Clarification (P95 Amendment – 2026-04-13)

The `NOT_SUPPORTED` annotations on `CheckpointApprovalService` are **architecturally correct for all persistence backends**, not just an SQLite workaround.

**Why `NOT_SUPPORTED` is semantically correct regardless of backend:**
* The 4 mutating methods orchestrate child `REQUIRES_NEW` writes **and** a subsequent `triggerResume()` which enters the transaction-free zone (§5.9). An outer `REQUIRED` transaction would hold a connection open across the LLM call in the resume path – this is harmful for **any** connection pool, not just SQLite.
* On Postgres, `NOT_SUPPORTED` is harmless dead weight: it suspends any ambient JTA context, which is fine since all children manage their own transactions.
* On SQLite, `NOT_SUPPORTED` is **critical**: it prevents the BUSY cascade (§5.8).

**What the storage extraction MUST NOT do:**
* MUST NOT move `NOT_SUPPORTED` annotations from `CheckpointApprovalService` into the adapter layer. The transaction boundary protects the *orchestration sequence* (persistence + resume), not just the persistence call.
* MUST NOT make `@Transactional` annotations "configurable per profile" on this class. The `NOT_SUPPORTED` contract applies universally.

**What the storage extraction MAY do:**
* The future `PostgresCheckpointStoreAdapter` MAY use `@Transactional(REQUIRED)` or plain `@Transactional` on its internal methods (no BUSY risk on Postgres).
* The `SqliteCheckpointStoreAdapter` MUST retain `REQUIRES_NEW` on its write methods (same as current `StatefulAgentStoreImpl.doXxx()` methods).

---

## 5.11 `ApprovalStateMachine.decide()` Transaction Contract

`ApprovalStateMachine.decide()` (the `ApprovalGateway` SPI implementation) MUST be
annotated `@Transactional(NOT_SUPPORTED)` or carry no transaction annotation.

It calls:
1. `agentStore.updateBatchToolDecision()` - `REQUIRES_NEW`
2. `agentStore.isAllBatchToolsDecided()` - `REQUIRES_NEW` read
3. `resumeHandler.resumeAfterBatchDecision()` - transaction-free zone

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
The next caller - completely unrelated to the approval that failed - receives the broken
connection and gets "cannot commit - no transaction is active" even though its own code
is correct. This is the cron cascade failure mode observed in P84.

**Why max-size must be ≥ 3:**
- Slot 1: `AsyncPersistenceEngine` critical drain thread (long-held, `REQUIRES_NEW`)
- Slot 2: `AsyncPersistenceEngine` telemetry drain thread
- Slot 3: Foreground approval writes / cron `REQUIRES_NEW` operations

A pool of 2 starves foreground approval writes whenever both drain threads are active.

---

## 5.14 `JdbiRetryWrapper` Nesting Contract

`JdbiRetryWrapper.withRetry()` replaces the deleted `SqliteRetryTemplate`. It wraps a JDBI
operation with exponential backoff on `SQLITE_BUSY`/`SQLITE_LOCKED` errors using the same
`SqliteErrorStrings.isBusy(e)` detection as its predecessor.

**Key differences from `SqliteRetryTemplate`:**
- `JdbiRetryWrapper` does NOT assume a `@Transactional(REQUIRES_NEW)` boundary — the caller
  manages its own transaction context via JDBI handles (`jdbi.useTransaction()`).
- It retries on `UnableToExecuteStatementException` and `TransactionException` (JDBI's
  wrappers for SQLite errors) in addition to raw `SQLiteException`.
- There is no JTA transaction nesting risk: JDBI handles are not enlisted in JTA, so
  calling `withRetry()` from inside an active `@Transactional` method is safe (though
  unnecessary — each JDBI handle manages its own transaction).

**MUST NOT use `JdbiRetryWrapper` for:**
- Non-database operations
- Read-only queries where retry is unnecessary

The wrapper inspects `SqliteErrorStrings.isBusy(e)` across the full `getCause()` chain.
Do not simplify this check to a single `e.getMessage()` string comparison.

---

### 5.14.1 Retry Encapsulation After Storage Extraction (P95 Amendment – 2026-04-13)

After the C1 storage port extraction, retry logic MUST be encapsulated **inside** the
`amsha-persistence-sqlite` adapter module.

**MUST NOT expose a `PersistenceRetryPort` interface to `amsha-core`.**

**Rationale:**
* Retry-on-BUSY is a SQLite-specific infrastructure concern. Core should call `CheckpointStorePort.save()` and the adapter handles retries transparently via `JdbiRetryWrapper`.
* Postgres does not need BUSY retries – it uses MVCC and standard connection-pool retry. Exposing a retry port to Core forces every core class to participate in an infrastructure concern it should not know about.
* `StatefulAgentStoreImpl` previously wrapped every `self.doXxx()` call with `withRetry()`. After extraction, the `SqliteCheckpointStoreAdapter` absorbs both the transaction boundary and the retry loop – the `withRetry` wrapper in `StatefulAgentStoreImpl` was deleted.
* `WorkspaceManager.markCheckpointsFailed()` delegates to `CheckpointStorePort.markFailed(sessionId)`. The SQLite adapter wraps its own retry internally via `JdbiRetryWrapper`.

**Port interface (`amsha-persistence-api`) MUST contain only:**
* `CheckpointStorePort` – checkpoint CRUD + status transitions
* `MemoryRetrievalPort` – search + index
* `BackendCapabilities` – feature flags (FTS, vector, advisory locks)

**Port interface MUST NOT contain:**
* `PersistenceRetryPort` or any retry abstraction
* `JdbiRetryWrapper` or any reference to SQLite error handling

---

## 5.15 Narayana XA Failure Cascade Prevention

When a `REQUIRES_NEW` transaction fails with `SQLITE_BUSY`:

1. SQLite auto-aborts the failing connection after `busy_timeout` ms.
2. Narayana attempts XA rollback on the failed branch - SQLite reports
   "cannot rollback - no transaction is active" (already aborted).
3. Narayana marks the XA branch as rolled back but the connection state is
   inconsistent.
4. **Without `background-validation`:** Agroal returns this broken connection to the
   pool. The next caller gets "cannot commit - no transaction is active" for its
   own innocent transaction.
5. **Cascade effect:** If the cron scheduler poll cycle's `reclaimStaleCrons()` owns
   the broken connection, the entire scheduler Narayana TX context is poisoned, and
   every `cronRepository` write in that cycle fails - even `claimForFire()` for
   completely unrelated jobs.

**Three prevention layers - ALL must be in place simultaneously:**

| Layer | Rule | Prevents |
|---|---|---|
| §5.8 | No REQUIRED → REQUIRES_NEW nesting | Eliminates BUSY at source |
| §5.13 | `background-validation=true` | Catches broken connections before reuse |
| §5.12 | `reclaimStaleCrons` isolation try/catch | Prevents cron cascade from approval failures |

If all three layers are in place, a single BUSY event is isolated to the failing
operation and cannot cascade to unrelated callers or threads.

---

## 5.16 `SessionReaper` GC Method Transaction Annotations

`SessionReaper.findAwaitingApprovalIds()` MUST be `@Transactional(NOT_SUPPORTED)`.
`SessionReaper.markApprovalTimedOut()` MUST be `@Transactional(NOT_SUPPORTED)`.

These are scheduled GC methods that delegate to individual `REQUIRES_NEW` write units.
Wrapping them in `REQUIRED` would create a long-lived connection that holds a write lock
for the entire GC sweep duration (potentially scanning many sessions).

---

## 5.17 `AsyncPersistenceEngine` Context Binding Rule

`AsyncPersistenceEngine` runs two virtual drain threads (`amsha-audit-critical-drain`,
`amsha-audit-telemetry-drain`). These are NOT CDI-managed threads.

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
`CronResultHandler.markCompleted()`. If the `COMPLETED` finalization JDBI transaction
has not fully committed to disk before the `terminateSession` query runs
(possible under concurrent load with Agroal pool pressure), the checkpoint may still
appear as `IN_PROGRESS` in the DB. Without the exclusion, it gets incorrectly overwritten
to `FAILED`, corrupting the audit trail and triggering spurious failure notifications.

MUST NOT add new terminal statuses to `CheckpointStatus` without updating this query.

---

## 5.19 Checkpoint Query Ordering - ORDER BY id DESC

Any JPQL or native query that retrieves "the most recent" checkpoint for a session
MUST include `ORDER BY c.id DESC LIMIT 1`.

**Why:** SQLite does not guarantee insertion order without an explicit ORDER BY. Without
`ORDER BY c.id DESC`, a `LIMIT 1` may return the oldest COMPLETED row, shadowing a
newer AWAITING_APPROVAL checkpoint. This produces the silent failure:
"No pending tool executions require approval" when there is one.

This applies to:
- `findBySessionAndRole(sessionId, agentRole)` - internal to `StatefulAgentStore`
- `findLatestAwaitingApproval(sessionId)` - exposed on `StatefulAgentStore` interface
- `findLatestAwaitingApprovalForCron(cronId)` - cron approval lookup

MUST NOT use `getFirstResult()` or `stream().findFirst()` without an explicit ORDER BY.

## 5.20 SecretVault Transaction Contract

SecretVault **mutation** operations (`store()`, `storeSystemKey()`, `removeSystemKey()`, and the user-scoped `remove(keyName, userId)`) MUST use `@Transactional(REQUIRES_NEW)` to prevent nesting with active tool or checkpoint transactions. User-scoped `store`/`get`/`remove(keyName, userId)` resolve a stable `USER:<userId>` partition (independent of the transient chat session) so durable MCP header credentials survive reconnect, async cleanup, rotation, and restart.

- Caller transactions MUST NOT rely on vault writes rolling back with their transaction. Vault writes are committed immediately.
- Calling `vault.store(...)` from within a `REQUIRED` transaction suspends the outer transaction and gets its own write lock, preventing `SQLITE_BUSY` errors during concurrent tool execution or onboarding initialization.
- This explicit boundary satisfies the anti-nesting requirements described in §5.8.
- **Read methods** (`get()`, `retrieveSystemKey()`, `systemKeyExists()`) do NOT need `@Transactional` — they are read-only and do not contend for write locks. Adding `SUPPORTS` is acceptable but unnecessary.

---

## 5.21 Instant/Timestamp Conversion (§5.21 INVARIANT)

- **SQLite:** `Instant` ↔ ISO-8601 string conversion is handled by JDBI type mappers registered in `JdbiProducer` (via `InstantArgumentFactory` / `InstantColumnMapper`).
- **PostgreSQL** handles `timestamptz` natively via its JDBC driver; no special conversion is needed.
- Use JDBI type mappers for `Instant` — do NOT add any `@Converter` annotation.

## 5.22 Cross-DB Column Definition Usage (§5.22 INVARIANT)
No DDL customization for IDENTITY bounds MUST live in per-adapter Flyway migration scripts.
JPA's `@Column(columnDefinition=...)` is no longer present — Hibernate has been removed from all modules.
All DDL is expressed in per-adapter Flyway migration SQL files only.

## 5.23 SessionSchemaEnforcer DDL Isolation (§5.23 INVARIANT)
All raw DDL `CREATE TABLE` and similar schema emission statements are adapter-specific and must never live in `amsha-core` or `amsha-app`. Each persistence adapter module (e.g. `amsha-persistence-sqlite`) owns its specific programmatic DDL (like `AUTOINCREMENT` mapping) completely isolated from core domain code.

## 5.24 Persistence Backend Selection (§5.24 INVARIANT)

The persistence backend is controlled by a single build property: **`amsha.persistence.backend`**.

### How It Works

Every persistence adapter bean uses Quarkus `@IfBuildProperty` to conditionally activate:

```java
// SQLite — DEFAULT (enableIfMissing = true means: if property is unset, SQLite activates)
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)

// PostgreSQL — opt-in only (no enableIfMissing)
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")

// Filesystem — opt-in only
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
```

### When Backend Selection Happens

| Mode | When `@IfBuildProperty` is evaluated | Switching backend |
|------|--------------------------------------|-------------------|
| **JVM** (`amsha serve`) | At **app startup** (runtime) | Set `AMSHA_PERSISTENCE_BACKEND` in `.env` — **no rebuild needed** |
| **Native image** (`amsha-native`) | At **build time** (GraalVM compile) | Must **rebuild** the native binary with the new property set during build |

### How to Switch Backend (JVM mode)

```bash
# 1. Set in .env (before starting the app)
echo "AMSHA_PERSISTENCE_BACKEND=postgres" >> .env

# 2. Also set connection details for the target backend:
echo "QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/amsha" >> .env
echo "QUARKUS_DATASOURCE_USERNAME=amsha" >> .env
echo "QUARKUS_DATASOURCE_PASSWORD=<password>" >> .env

# 3. Start the app — no rebuild needed
amsha serve
```

### How to Switch Backend (Native image)

```bash
# Must rebuild — @IfBuildProperty is baked into the native image at compile time
export AMSHA_PERSISTENCE_BACKEND=postgres
./mvnw package -Dnative
```

### Backend-Specific Configuration

| Backend | `.env` variables needed | Notes |
|---------|------------------------|-------|
| **SQLite** (default) | None — zero config | Data stored in `./amsha-data/` by default |
| **PostgreSQL** | `QUARKUS_DATASOURCE_JDBC_URL`, `QUARKUS_DATASOURCE_USERNAME`, `QUARKUS_DATASOURCE_PASSWORD` | Flyway auto-creates schema on startup |
| **Filesystem** | `AMSHA_FS_DATA_DIR` (optional, default `./data/fs`) | Uses Lucene index for BM25 search |

### Persistence Implementation Parity

![Identity persistence parity](assets/amsha_persistence_parity.svg)

Every persistence port used by identity and workspace access must have all
three implementations enabled and kept semantically aligned:

| Backend | Implementation | Concurrency mechanism for identity membership removal |
|---|---|---|
| SQLite | `persistence-sqlite` `SqliteIdentityStoreAdapter` | Atomic conditional delete in one SQL statement |
| PostgreSQL | `persistence-postgres` `PostgresIdentityStoreAdapter` | Transaction-scoped advisory lock plus conditional owner count |
| Filesystem | `persistence-fs` `FsIdentityStoreAdapter` | Synchronized owner-count check and delete |

In particular, `IdentityStorePort.deleteMembershipIfNotLastOwner(...)` must
never be implemented as a separate owner count followed by an unconditional
delete. Concurrent requests must not remove the final active `OWNER`, and
external identity ownership must not be reassigned silently when the provider,
issuer, and subject key already belongs to another user.

Backend selection changes the active storage implementation; it does not copy
or migrate existing data. Switching an existing deployment therefore requires
an explicit data migration or a newly initialized backend, plus its backend-
specific connection or data-directory configuration.

### Why SQLite Is the Default

- `enableIfMissing = true` on the SQLite adapter means: if the property is not set at all, SQLite activates.
- This ensures the binary works out of the box with zero configuration — no database server needed.
- All three backend jars are included in the build; `@IfBuildProperty` selects which CDI beans register at startup.

### Flyway Migration Parity

Every Flyway migration must exist in both backends with the same version number:
- SQLite: `persistence-sqlite/src/main/resources/db/migration/sqlite/V<n>__<name>.sql`
- PostgreSQL: `persistence-postgres/src/main/resources/db/migration/postgresql/V<n>__<name>.sql`

Flyway runs automatically on startup — the user does not need to manually create tables.

### Tiered Memory Backend

The tiered memory subsystem has its own separate backend selection:
```yaml
amsha:
  memory:
    tiered:
      backend: ${AMSHA_MEMORY_TIERED_BACKEND:sqlite}
```

This is a **different property** from `amsha.persistence.backend`. The tiered memory backend can be different from the main persistence backend (e.g., main DB on Postgres, tiered memory on SQLite). Both default to `sqlite`.

### Native Image Binary Naming Convention (§5.24.1)

Since `@IfBuildProperty` is baked at build time, each persistence backend requires a **separate native binary**. The naming convention is:

| Binary | Backend | Property value | Description |
|--------|---------|---------------|-------------|
| `amsha-sqlite` | SQLite | `sqlite` (default) | Zero-config, embedded database |
| `amsha-pg` | PostgreSQL | `postgres` | Requires running PG instance |
| `amsha-fs` | Filesystem | `fs` | Lucene-based, no SQL database |

**Build approach:** Use `mise run` tasks (recommended) or raw Maven commands:

```bash
# Recommended: mise tasks (auto-switch JDK, copy to dist/)
mise run build:native-local          # SQLite (default) → dist/amsha
mise run build:native-local-pg       # PostgreSQL → dist/amsha-pg
mise run build:native-local-fs       # Filesystem → dist/amsha-fs
mise run build:native-local-all      # All 3 variants → dist/amsha, dist/amsha-pg, dist/amsha-fs
```

**What mise tasks do:**
1. Auto-switch JDK to GraalVM Community 25 (via `mise use java@graalvm-community-25.0.2`)
2. Bake `quarkus.amsha.build.backend` property into `build-info.properties`
3. Build native image with memory auto-tuning (85% of available RAM)
4. Copy final binary to `dist/` with correct name (`amsha`, `amsha-pg`, `amsha-fs`)
5. Switch JDK back to Temurin 25 after build

**Why use mise tasks:** Raw Maven commands require manual JDK switching, manual binary renaming, and don't auto-tune memory. Mise tasks handle all of this automatically.

### Release Archive Distribution (§5.24.2)

Each OS-specific release archive contains **all 3 backend variants**:

```
amsha-0.1.0-linux-x64.tar.gz
  ├── amsha-sqlite     # SQLite (default)
  ├── amsha-pg         # PostgreSQL
  └── amsha-fs         # Filesystem

amsha-0.1.0-macos-aarch64.tar.gz
  ├── amsha-sqlite     # SQLite (default)
  ├── amsha-pg         # PostgreSQL
  └── amsha-fs         # Filesystem

amsha-0.1.0-windows-x64.zip
  ├── amsha-sqlite.exe # SQLite (default)
  ├── amsha-pg.exe     # PostgreSQL
  └── amsha-fs.exe     # Filesystem
```

This is **3 archives × 3 binaries each = 9 binaries total** across all platforms.

**Why all 3 in one archive:** The user downloads one file for their OS and picks the binary matching their database. No separate downloads per backend.

**Default binary (`amsha-sqlite` / `amsha-sqlite.exe`)** is always the SQLite variant — works out of the box with zero configuration. Users who want PostgreSQL or Filesystem use `amsha-pg` or `amsha-fs` respectively.

**JVM mode is backend-agnostic:** The JAR distribution (`quarkus-run.jar`) includes all 3 backend jars. The user switches via `.env` at runtime — no separate JARs needed.

---

## 5.25 Checkpoint filter terminal statuses

Checkpoint queries in the persistence layer MUST treat `DENIED` as a terminal
status alongside `FAILED`, `COMPLETED`, `ARCHIVED`, and `COMPROMISED`.

- `FsCheckpointStoreAdapter` MUST include `DENIED` in terminal status filters.
- `isCheckpointExpired()` MUST narrow its check to `AWAITING_APPROVAL` only,
  not all non-terminal statuses. A `DENIED` checkpoint is terminal and must
  NOT be overwritten by expiration logic.
- Violation: Treating `DENIED` as non-terminal causes spurious resume attempts
  and corrupts the checkpoint lifecycle state machine.

---

## 5.26 Backend Parity for All Store Ports (§5.26 INVARIANT)

When adding or modifying any `*StorePort` interface, ALL 3 backend implementations MUST be
created/updated simultaneously:

- `amsha-persistence-sqlite` (SQLite — default, `@IfBuildProperty(enableIfMissing = true)`)
- `amsha-persistence-postgres` (PostgreSQL — `@IfBuildProperty("postgres")`)
- Either `amsha-persistence-fs` (Filesystem — `@IfBuildProperty("fs")`) OR the store lives
  in the `self-evolve` module with `persistence-fs` wrappers

Flyway V<n> migrations must exist in both `persistence-sqlite` and `persistence-postgres`
(FS has no migrations).

**Affected ports** (all MUST have 3 backend implementations):

| Port | SQLite | PostgreSQL | Filesystem |
|---|---|---|---|
| `CoreMemoryStorePort` | `SqliteCoreMemoryStore` | — | `FsCoreMemoryStore` |
| `RecallMemoryStorePort` | `SqliteRecallMemoryStore` | `PostgresRecallMemoryStore` | `FsRecallMemoryStore` |
| `ArchivalMemoryStorePort` | `SqliteArchivalMemoryStore` | `PostgresArchivalMemoryStore` | `FsArchivalMemoryStore` |
| `EvolutionEventStorePort` | `SqliteEvolutionEventStore` | `PostgresEvolutionEventStore` | `FsEvolutionEventStore` |
| `ExtractedMemoryStorePort` | `SqliteExtractedMemoryStore` | `PostgresExtractedMemoryStore` | `FsExtractedMemoryStore` |

**Verification**: grep for `@IfBuildProperty` and `@TieredBackend` across all `*Store*.java` files —
each port should have matches for all 3 backends.

**Known failures**:
- P104: Phase 8/9 self-evolve stores were built for SQLite + PostgreSQL but the FS backend was missed entirely.
- P104b: Tiered memory stores (`CoreMemoryStorePort`, `RecallMemoryStorePort`, `ArchivalMemoryStorePort`) had no `@TieredBackend("fs")` implementations — runtime error `No @TieredBackend("fs") RecallMemoryStorePort implementation found`.
- P104c: `%filesystem` profile in `application.yaml` was missing `amsha.memory.tiered.backend: fs` — caused tiered memory to default to SQLite while FS backend was active.

This rule prevents recurrence of all three patterns.

---

### 5.27 Session Catalog Invariants (§5.27 INVARIANT)

The `SessionCatalogStore` port maps `(ownerKey, channelType, channelId)` scopes to durable
`SessionCatalogEntry` records. Every session (CLI, Telegram, WebSocket, REST) is catalogued
from birth with status `ACTIVE`, `INACTIVE`, or `ARCHIVED`.

**Partial unique index — "at most one active per scope":**

Both migrations (`V6__interaction_catalog.sql`) MUST contain the partial unique index, not a
plain `UNIQUE` on `(owner_key, channel_type, channel_id, active)`. A plain 4-tuple UNIQUE
would reject rotation — multiple inactive siblings share `active = false`:

```sql
-- SQLite
CREATE UNIQUE INDEX IF NOT EXISTS idx_session_catalog_active
    ON session_catalog(owner_key, channel_type, channel_id) WHERE active = 1;

-- PostgreSQL
CREATE UNIQUE INDEX idx_session_catalog_active
    ON session_catalog(owner_key, channel_type, channel_id) WHERE active = TRUE;
```

**`activateNew` atomicity:** all three adapters (SQLite, PostgreSQL, FS) MUST implement
`activateNew(...)` as an atomic deactivate-then-insert operation. The SQL adapters use a
single JDBI `useTransaction` block; the FS adapter uses `FsLockManager.tryLock`. This
prevents a concurrent insert from violating the partial unique index.

**`seq` is adapter-assigned:** the store adapter computes `seq = max(seq in scope) + 1`
when the provided value is `0`/unset. The service layer never computes `seq`. This keeps
`/resume <number>` consistent across all three backends.

**Backend parity:** `SessionCatalogStore` has three adapter implementations
(`SqliteSessionCatalogAdapter`, `PostgresSessionCatalogAdapter`, `FsSessionCatalogAdapter`).
All three must be created/updated simultaneously per §5.26.

**`touch` never inserts:** `touch(...)` updates `lastUsedAt` on an existing row. It MUST
NOT insert a new row — creation of missing catalog rows is handled exclusively by
`resolveOrCreateSession` through `activateNew`. A bare-active insert from `touch` would
violate the partial unique index on a fully populated scope.

**Archiving is non-resumable:** `archive(sessionId)` sets `status = 'ARCHIVED'` and
`active = false`. Any attempt to `resume(...)` an `ARCHIVED` session MUST be rejected.
The `SessionReaper` daily sweep calls `listInactiveBefore(cutoff)` and archives each
inactive entry older than `amsha.session-catalog.archive-after-days` (default 90).
Active and awaiting-approval sessions are never candidates.

**Legacy migration:** `SessionCatalogService.resolveOrCreateSession` queries the catalog
first. If a scope has no catalog entry, it falls back to the pre-existing
`SessionIdResolver.resolveOrCreate(...)` and lazily migrates the legacy session ID
into the catalog through `activateNew`. This keeps `SessionRegistry` (SQL) and
`FallbackSessionIdResolver` (FS) unchanged as pre-catalog fallbacks.

---

## 5.28 Embedding Vector-Index Descriptor Safety (§5.28 INVARIANT)

Every durable or rebuildable vector index MUST be bound to an
`EmbeddingIndexDescriptor` before vector ingestion or retrieval is enabled. Dimension
equality alone is insufficient. The fingerprint MUST include the canonical embedding
space, immutable model and tokenizer revisions, preprocessing, pooling, normalization,
numeric precision, and chunking/window/aggregation contract. Provider or transport
identity is retained as provenance but MUST NOT determine compatibility.

- An empty index may initialize or replace its descriptor with the active descriptor.
- A non-empty index with missing, invalid, or mismatched metadata MUST preserve all
  rows/documents, disable only vector operations, and retain safe BM25/FTS/text search.
- Startup MUST NOT delete, re-embed, or silently relabel existing vectors. Development
  reset is an explicit operator action: delete the configured SQLite/filesystem data
  directory or recreate the PostgreSQL development database/volume.
- SQLite and PostgreSQL descriptor schema changes MUST use matching Flyway versions and
  equivalent semantics. Filesystem Lucene MUST use a versioned checksummed manifest
  written atomically in the index root.
- Retrieval, ingestion, capability reporting, and readiness diagnostics MUST consume
  the same `VectorIndexMetadataPort` gate. A descriptor mismatch reports bounded
  `DEGRADED` vector capability while overall readiness remains `UP` when text fallback
  is safe. Exact revisions and fingerprints belong only in protected diagnostics, not
  metric labels or public readiness payloads.

The active namespace inventory is explicit: SQLite global RAG and archival vec0;
PostgreSQL RAG pgvector; and filesystem Lucene RAG. The current PostgreSQL archival
embedding column is dormant because `PostgresArchivalMemoryStore` neither writes nor
queries it, and filesystem archival memory is text-only. A dormant schema column MUST
NOT be advertised or health-checked as an active vector namespace until its complete
ingestion, query, fallback, and descriptor-gating path lands.

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

## 6.5 @RunOnVirtualThread enforcement (Revised — Post-JDBI)

`RestResourceBuildStep` was NOT removed — it was restructured to enforce `@RunOnVirtualThread` on ALL `com.amsha` REST resource classes, not just those with `EntityManager` injection. The build step validates that every `@Path`-annotated class carries the `@RunOnVirtualThread` annotation, failing the build if any resource lacks it.

### 6.6 Static run-time objects
- MUST NOT store JDK or library objects that require run-time initialization (e.g. `java.net.http.HttpClient`, `java.lang.Thread`, or descriptors) in `static` or `static final` fields when building for native-image.
- GraalVM attempts to store these objects in the "image heap" at build time, leading to `UnsupportedFeatureException` (An object of type ... was found in the image heap).
- Use `@ApplicationScoped` instance fields or `@PostConstruct` lazy initialization to ensure these objects are created at run-time.
- **6.7 Module-specific Reflection Config**: Evaluate every new type for native-image reflection. Records used for JSON/JDBI, JDBI DAOs, JDBI DTO rows, and persistence-module CDI beans require registration; ordinary CDI beans, enums, config mappings, and qualifiers do not. Add registrations to the module’s existing `*ReflectionConfig` when needed. See [AGENTS.md §8](../AGENTS.md#8-graalvm-native-image-reflection-registration).
- **6.8 Native-Image Runtime Initialization**: Libraries using Foreign Function & Memory (FFM) API, `MethodHandle`, or native access (e.g., Lucene 10.x `PosixNativeAccess`) MUST be marked for run-time initialization in `app/src/main/resources/META-INF/native-image/com.amsha/app/native-image.properties`. Mark the **entire package** (`--initialize-at-run-time=org.apache.lucene`), not individual classes — GraalVM embeds static fields from build-time classes into the image heap, causing whack-a-mole errors when marking classes individually. Also mark any CDI beans with `@PostConstruct` that trigger the library. Do NOT use `pom.xml` `additional-build-args` for this — Quarkus splits on commas, breaking args that contain commas. See [AGENTS.md §8](../AGENTS.md#8-graalvm-native-image-reflection-registration) for detailed guidance.

### 6.9 Lucene `TestSecrets` Class.forName Registration (§6.9 INVARIANT)

Lucene 10.x ships `org.apache.lucene.internal.tests.TestSecrets` in the **production** `lucene-core` jar. Its static initializer calls `Class.forName("org.apache.lucene.index.ConcurrentMergeScheduler")` at runtime. In native-image, GraalVM's static analysis removes `ConcurrentMergeScheduler` because nothing directly references it — but `Class.forName()` is a runtime reflective call that cannot be analyzed at build time, resulting in `ClassNotFoundException` → `ExceptionInInitializerError` → crash on startup.

**Fix:** Add a `reflect-config.json` to the module that depends on `lucene-core` (currently `amsha-persistence-fs`):

```json
[
  { "name": "org.apache.lucene.index.ConcurrentMergeScheduler", "allDeclaredConstructors": true, "allPublicMethods": true },
  { "name": "org.apache.lucene.index.SegmentReader", "allDeclaredConstructors": true, "allPublicMethods": true },
  { "name": "org.apache.lucene.index.IndexWriter", "allDeclaredConstructors": true, "allPublicMethods": true },
  { "name": "org.apache.lucene.store.FilterIndexInput", "allDeclaredConstructors": true, "allPublicMethods": true }
]
```

These are the 4 classes referenced by `TestSecrets.<clinit>` via `Consumer.accept()` in a lambda (decompilable from `TestSecrets.class` constant pool). All four MUST be registered — missing any one causes `ExceptionInInitializerError` → crash on startup.

**MUST NOT** register inner classes (e.g., `ConcurrentMergeScheduler.MergeThread`) as class literals in `@RegisterForReflection` — they have `protected`/`private` access and will not compile. Use the JSON config approach instead.

**Detection:** GraalVM native-image build will NOT catch this — the error only manifests at **runtime** when the static initializer fires. Always verify with `mise build:native-local` and a test that boots the full application context.

### 6.10 SQLite/vec0 Virtual Table Shadow Table Cleanup (§6.10 INVARIANT)

FTS5 and vec0 virtual tables create internal **shadow tables** (e.g., `recall_messages_fts_data`, `vec_archival_info`) that Flyway's SQLite `clean` command cannot drop directly — it sees them as regular tables but they're owned by the virtual table module.

**Fix:** `FtsCleanCallback` (a `FlywayConfigurationCustomizer` that fires `BEFORE_CLEAN`) MUST drop all virtual tables before Flyway attempts its own cleanup:

```java
stmt.execute("DROP TABLE IF EXISTS recall_messages_fts");
stmt.execute("DROP TABLE IF EXISTS archival_chunks_fts");
stmt.execute("DROP TABLE IF EXISTS vec_archival");
```

**MUST:** Add every new FTS5 or vec0 virtual table to `FtsCleanCallback.handle()`. Failure to do so causes `FlywaySqlException: Unable to drop "main"."<shadow_table>"` on test startup with `clean-at-start=true`.

### 6.11 pgvector Graceful Degradation in Flyway (§6.11 INVARIANT)

The `CREATE EXTENSION IF NOT EXISTS vector` statement in `V4__memory.sql` MUST be wrapped in a `DO $$ EXCEPTION WHEN OTHERS $$` block so Flyway migration completes successfully in environments where pgvector is not installed (e.g., embedded Postgres in tests, CI without pgvector):

```sql
DO $$ BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pgvector not available — archival vector search degraded to BM25-only';
END $$;
```

The `embedding vector(768)` column and HNSW index MUST be similarly guarded with `IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector')`.

**Rationale:** Tests use embedded Postgres (Testcontainers / `io.zonky.test`) which does not have pgvector installed. Unconditional `CREATE EXTENSION vector` fails with `could not open extension control file` and blocks all Postgres tests.

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

### 7.5 WebSocket channel invariants

- `amsha-channel-web` MUST NOT import `com.amsha.core.*` directly (enforced by `ArchitectureBoundaryTest`).
- WebSocket session state (`WebSocketSessionStore`) MUST be `@ApplicationScoped` with `ConcurrentHashMap` — no per-session CDI scopes for WebSocket connections.
- HITL sessions MUST survive WebSocket disconnects — `WebChatEndpoint.onClose()` MUST check for `AWAITING_APPROVAL` before terminating the session.
- The WebSocket endpoint path is `/ws/chat` (sessionId is in the message or query parameter, not in the URL path).

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
    Do NOT use UUID for callIds - LangChain4j requires stable,
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
     issues parallel tool calls - most parallel call traffic will
     come from Anthropic and OpenAI providers.

7.11 MUST NOT: Re-introduce a single-tool fast-path in `ReasoningStepParser`.
     All LLM tool-call responses are processed as a list (N ≥ 1).
     The `getFirst()` fallback was removed and MUST NOT be restored
     as a silent fallback - it hides multi-tool responses from the batch pipeline.

7.13 MUST: OpenTelemetry span context MUST be explicitly propagated
     to virtual threads. Capture io.opentelemetry.context.Context.current()
     on the calling thread BEFORE submitting to the executor. Restore it
     inside each virtual thread via context.makeCurrent(). ContextAwareExecutorService
     propagates workspace context but does NOT propagate OTel ThreadLocal spans.
     (P81 Confirmation: Verified in ParallelToolStepExecutor.java lines 173/192)

7.13.1 INVARIANT: Blocking guards must test the actual executing thread, not
     merely the type of an attached Vert.x context. A REST method annotated with
     `@RunOnVirtualThread` can retain an event-loop-derived context while running
     safely on a virtual thread. Use `Context.isOnEventLoopThread()`; do not infer
     the current thread from `Vertx.currentContext().isEventLoopContext()`.

7.13.2 INVARIANT: Phase 2.5 post-policy tool-risk decision SPIs
     (`PostPolicyToolRiskInterceptor`) MUST execute only after `ToolInvocationGuard`
     validation, deduplication, tool recognition, and deterministic HITL checks produce a
     provisional `Allow`. A pre-policy `ToolGuardInterceptor` returning `Allow` MUST continue
     through the remaining interceptors and every built-in check. Post-policy interceptors MUST
     be ordered by ascending priority with binary class name as the stable tie-breaker and MUST
     compose monotonically: `BLOCK` outranks `REQUIRE_APPROVAL`, which outranks `NoOpinion`.
     They MUST NEVER bypass or weaken an existing block, deduplication result, HITL boundary,
     role schema, or autonomy rule. `REQUIRE_APPROVAL` MUST reuse the existing
     `PauseForApproval`/`ApprovalFrame` path. On classifier error, circuit-breaker trip, model
     absence, or no optional implementation, `PostPolicyToolRiskDispatcher` MUST preserve the
     provisional allow through `NoOpinion`; metrics MUST remain bounded and payload-free.

7.13.3 INVARIANT: Phase 2.5 `AnswerQualityInterceptor` SPIs MUST be evaluated in
     `FinishActionHandler` only after the authoritative `AnswerGuardStrategy` accepts the answer
     candidate. Interceptors MUST use stable priority/class-name ordering. A `REQUEST_RETRY`
     opinion may be honored only when no tool has been invoked in the current turn and
     `context.tryConsumeAnswerQualityRetry()` succeeds. All interceptors share that single retry
     budget. A retry MUST NEVER be requested after a tool boundary, preventing re-entrant side
     effects or duplicate tool execution. Missing implementations and isolated failures MUST
     preserve the accepted answer without logging answer or goal payloads.

7.13.4 INVARIANT: Phase 3 classifier runtime control MUST distinguish startup/requested
     state from effective enablement. Effective enablement additionally requires a ready,
     promotion-approved active implementation registration. Shadow classifiers MUST NOT
     register as active implementations or acquire active leases. Enabling without a ready
     implementation MUST fail closed. Disabling MUST atomically reject new leases while
     accepted leases complete unchanged and drain. Intent, tool-risk, and answer-quality
     state MUST remain independent. Mutating admin endpoints MUST require `isInternal()`
     and record a mandatory, payload-free audit event before mutation; audit failure MUST
     leave state unchanged. Control responses and audit details MUST NOT contain model IDs,
     inputs, outputs, scores, labels, goals, answers, or tool parameters.

7.14 INVARIANT: ReasoningStep history is the in-memory source of truth
     during AgentLoop.runWithRole() execution. ChatHistoryAssembler reads
     from in-memory history on each LLM call - SQLite does not need to
     be current mid-run. AgentLoop persists history to SQLite via
     agentStore.save() at session end (Finished/Failed/LoopExceeded) or
     HITL pause (AwaitingApproval checkpoint). ChatMemoryWriter manages
     user goal/AI answer messages in ChatMemoryStore only - it MUST NOT
     be used for ReasoningStep history persistence.
     FinishActionHandler MUST NOT call agentStore.save() for ReasoningStep history.
     It returns AgentResult only. AgentLoop owns all persistence triggered by that result.
     FinishActionHandler MUST NOT own ChatMemoryWriter. The caller (AgentStateMachineRunner)
     calls writer.saveAnswer() after receiving the result.

7.15 MUST NOT: A deduplicated tool in a parallel batch MUST NOT cause
     its peers to be skipped. The dedup tool receives its cached
     observation as its result. All other tools in the batch proceed
     to execution normally.

7.16 SHOULD: Each parallel tool execution MUST time out after
     amsha.parallel.tool-timeout-seconds (default: 60). A timed-out
     tool returns withResult("Tool timed out after Xs", false) and
     does not block the rest of the batch.

7.17 MUST: Metrics counters amsha.agent.tool.calls MUST reflect
     individual tool names in a parallel batch - not the __parallel__
     sentinel. The MetricsAgentObserver already receives individual
     onStepComplete() calls from ParallelToolStepExecutor (one per
     tool) - verify the sentinel step itself does NOT trigger an
     additional __parallel__ counter increment.

7.18 INVARIANT: ReasoningStep history list is mutated EXACTLY ONCE
     per parallel batch execution - inside buildCompletedBatchDecision().
     Do NOT add intermediate steps for dedup results or partial
     executions. The single batch step encodes ALL N results.

7.19 INVARIANT: ParallelToolCall.callId() MUST be non-null at the point
     the merge loop executes. ReasoningStepParser MUST generate a
     UUID fallback when ToolExecutionRequest.id() is null (Ollama). 
     Never use .equals() on a potentially-null callId - use Objects.equals().

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
     amsha.hitl.timeout-hours (default: 24) MUST be auto-denied
     by the SessionReaper scheduled sweep. The user MUST receive a
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

§7.31 INVARIANT: A tool is considered "pre-executable" if and only if: (a) the current session's autonomy level is FULL_AUTONOMY, OR (b) the tool name appears in amsha.hitl.pre-executable-tools config list. Safe tools are never automatically inferred - they must be explicitly configured. This prevents accidental side effects from misclassification.

§7.32 MUST: The HITL pause message shown to the user MUST list ONLY the tools that still require approval. Pre-executed tools MUST NOT appear in the keyboard or the approval text. The Approve All / Deny All buttons operate only on the PENDING tools.

§7.33 INVARIANT: Approval delegation is scoped to a single session. An admin (user listed in amsha.hitl.delegate-approver-ids) may issue /approve or /deny for ANY active AWAITING_APPROVAL session by providing the sessionId: "/approve <sessionId>". The delegate action is audit-logged with both the admin userId and the original session owner's userId. Delegation MUST NOT bypass per-tool granularity - /approve <sessionId> <toolName> is also valid.

§7.34 MUST: Web UI tool approval MUST be per-tool granular. The REST HITL endpoint MUST accept toolName as a parameter (in the JSON request body via CheckpointApprovalRequest). Passing no toolName is valid only for bulk Approve All / Deny All operations.

§7.35 TelegramCallbackHandler MUST reject any callback whose sessionId maps to a checkpoint older than amsha.hitl.timeout-hours before routing to any reflex. This guard is independent of and complementary to the WorkspaceManager GC auto-deny scheduler (§7.26).

§7.36 MUST: The `ApprovalStateMachine` in `amsha-core` is the single source of truth for HITL state transitions. It MUST implement the `ApprovalGateway` SPI and enforce idempotency (ALREADY_DECIDED) at the core layer. Channels delegate callbacks directly to this gateway without local state checks.

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

### 8.6 Tool API key integration patterns

Tools requiring API keys use one of two patterns:
- **Declarative**: `@RequiresSecret(envVar, vaultKey)` on `sandboxCommand()` — resolved by `SandboxSecretResolver` and injected via `TmpfsSecretTransport` into the container. Used by `GithubTool`.
- **Programmatic**: `vault.systemKeyExists()` + `vault.retrieveSystemKey()` with `@ConfigProperty` fallback. Used by `BraveWebSearchTool` and `SerperWebSearchTool` for optional API keys.

Both patterns are valid. Tools that check `vault.systemKeyExists()` for `isEnabled()` while falling back to config properties are acceptable for optional tools that degrade gracefully when the key is absent.

### 8.7 LLM Guardrail Layer (Layer 7 — PreLlmGuard / PostLlmGuard)

The guardrail layer sits between the existing reflex pipeline (Layers 2–3 via `ReflexRegistry`) and the LLM provider call. It uses Amsha Agent-native guard interfaces — NOT LangChain4j's experimental `InputGuardrail`/`OutputGuardrail` — to avoid coupling to an evolving framework API.

**Architecture rules:**

1. **PreLlmGuard** MUST validate input before it reaches the LLM. Implementations include:
   - `InjectionInputGuard` (`@Priority(100)`) — delegates to `PromptInjectionReflex.matches()`, returns `Block` on match.
   - `PiiInputGuard` (`@Priority(200)`) — delegates to `PiiScanReflex.sanitize()`, returns `Sanitize` if redaction occurred.

2. **PostLlmGuard** MUST validate output after the LLM responds. Implementations include:
   - `ParseabilityOutputGuard` (`@Priority(100)`) — validates non-empty response and length limits.

3. **GuardrailCoordinator** MUST:
   - Discover all `PreLlmGuard` and `PostLlmGuard` beans via CDI `Instance<>`.
   - Sort by `@Priority` (lower = runs first).
   - Emit an OTel span for every guard decision.
   - Emit an `AuditService` event for every guard decision.
   - Run guards sequentially — each guard receives the (possibly sanitized) output of the prior guard.

4. **GuardDecision** sealed interface defines exactly four outcomes:
   - `Allow` — proceed unchanged.
   - `Block(String reason, String detail)` — stop processing, surface the reason.
   - `Retry(String reason, String correctedInput)` — signal the caller to retry with corrected input.
   - `Sanitize(String sanitizedInput)` — continue with modified input.

5. **Existing reflexes MUST NOT be replaced.** The guardrail adapters (e.g., `InjectionInputGuard`) reuse reflex logic at the LLM boundary without removing the reflexes from the earlier pipeline layers. Both layers remain active.

6. **Retry policy belongs to the reasoning loop.** Guards report a `Retry` decision; they do not invoke the model themselves.

7. **Streamed responses** MUST be fully accumulated before `PostLlmGuard` runs. The guard layer operates on the complete response, not partial chunks.

8. **Dependency rule:** `amsha-core` MUST NOT depend on `dev.langchain4j:langchain4j-guardrails`. The LC4J guardrails module is optional and may be added as a separate adapter only if built-in guardrail implementations (e.g., `MessageModeratorInputGuardrail`) are needed later.

### 8.8 Browser automation (PlaywrightCliTool)

- `PlaywrightCliTool` MUST validate URLs against `SAFE_URL_PATTERN` (`^https?://.*`) before navigation. File://, javascript:, and data: URLs are FORBIDDEN.
- Content extraction MUST be capped at `MAX_CONTENT_CHARS` (9092).
- The tool MUST be disabled by default (`amsha.tools.playwright.enabled=false`).
- Browser automation MUST NOT execute in `READONLY` autonomy level.

## 8.9 Sandbox tool exception contract

`SandboxExecTool.execute()` MUST follow this exception contract:
- `BudgetExceededException` - MUST propagate (not catch); `AgentStateMachineRunner` handles it.
- `ToolExecutionException`, `TimeoutException`, `SecurityException`, `IOException` - MUST catch
  and return `ToolResponse.failure(message)`. Never propagate to `ToolInvoker`.
- Output exceeding amsha.sandbox.max-output-chars (default 8192) MUST be truncated at that limit with suffix
  `[output truncated at max output chars limit]`. Never include raw Java stack traces in tool output.


---

## 9. Performance and reliability rules

### 9.1 Regex/pattern compilation
- Reusable regex/pattern objects must be `static final`.
- Do not compile patterns per request, per message, or inside hot-path methods.
- All Pattern objects in AnswerQualityGuard MUST be static final.
- Per-call Pattern.compile() in any answer validation or stripping path is FORBIDDEN.
  This includes stripRawToolJson() - NVIDIA_STRIP_PATTERN must be static final.

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
  amsha.skills.hot-reload: false

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
to the Quarkus CDI container - no error, no warning, just missing beans.
This applies to every `amsha-channel-*` module added in P67 and beyond.

### 15.1 Verification requirement
- Compilation success + unit test pass in isolation is NOT proof of CDI discovery.
- After adding a new module's dependency to `app/pom.xml`, verify CDI bean initialization by
  grepping the startup log for bean-specific log messages (e.g., `[SelfEvolve] Enabled`).
- A `@Startup @ApplicationScoped` bean that never logs at startup means the module is
  invisible to the Quarkus CDI container.

### 15.2 Known failure pattern: P104 self-evolve module
The `amsha-self-evolve` module was created with all CDI annotations (`@Startup`,
`@ApplicationScoped`, `@ObservesAsync`) but was **silently ignored** at runtime because
both `beans.xml` and `jandex-maven-plugin` were missing. The module compiled, all 36
unit tests passed in isolation, and the dependency was declared in `app/pom.xml`,
yet zero beans were instantiated. The only symptom was the absence of expected log lines.
Always verify with a runtime startup log check when adding CDI beans from a new module.

---

## 16. New Module Creation

### 16.1 Dependency Cycle Prevention
- NEW modules MUST NOT depend on `amsha-app`. 
- `amsha-app` is the "leaf" of the dependency tree for runtime features.
- If a new module needs logic from `amsha-app` (e.g., `LlmModelFactory`), that logic MUST be extracted into an SPI/Gateway in `amsha-core`.
- The new module depends only on the SPI interface; `amsha-app` provides the `@ApplicationScoped` implementation.

### 16.2 Test Configuration Boilerplate
- EVERY new module MUST include `src/test/resources/application.yaml`.
- The test configuration MUST provide dummy values for core properties required by scanned beans:
  - `amsha.vault.pepper`: "mock-pepper"
  - `quarkus.langchain4j.openai.timeout`: "30s"
  - `amsha.data-dir`: "target/data"
- The project uses **JDBI**, not Hibernate. Do NOT add `quarkus-hibernate-orm` dependency.
  Set `quarkus.hibernate-orm.enabled: false` in test config and omit any `hibernate-orm.packages` config.

### 16.3 CDI Discovery (Jandex)
- Refer to §15. Every module MUST have `jandex-maven-plugin` and `beans.xml`.
- Without Jandex, internal beans will be **silently ignored** when the module is consumed.
- Compilation and unit test pass in isolation is NOT sufficient proof. After wiring the
  module into `amsha-app`, verify CDI discovery by grepping startup logs for bean-init
  messages (e.g., `[SelfEvolve] Enabled`). See §15.1-15.2 for the full protocol.

### 16.4 Session Management (§16.4 INVARIANT)
- Amsha Agent primarily uses RESTEasy Reactive (`quarkus-rest`).
- Since RESTEasy Reactive does NOT support CDI `@SessionScoped` by default (due to bypassing the Servlet layer), wizard state (like onboarding) MUST use `@ApplicationScoped` if session-isolated state is not strictly required by the multi-user runtime model.
- If true session isolation is required in a Reactive module, implement a manual session registry keyed by a `UUID` stored in a secure Cookie.

### 16.5 Core Mocking in Tests
- Tests that trigger bean-scanning of core handlers MUST satisfy their dependencies via `@ApplicationScoped` mocks in `src/test/java`:
  - `IntentDetector` (satisfies `IntentClassificationHandler`)
  - `AgentChatModelProvider` (satisfies `ChatModel` injections)
  - `ChatModel` with `@Identifier("compaction")` (satisfies `CompactionService`)

### 16.6 JVM Shutdown Hang in Tests (§16.6 INVARIANT)
- Some Quarkus modules (especially those with `amsha-mcp` or `AsyncPersistenceEngine`) may hang for 30+ seconds during JVM shutdown in tests because of non-daemon background threads.
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
- For any new module providing CDI beans consumed by the main application, the module MUST be added to the `quarkus.index-dependency` section of `app/src/main/resources/application.yaml`.
- Failure to do so may trigger `jakarta.enterprise.inject.UnsatisfiedResolutionException` for core beans (e.g. `DevSecretSeeder`) during the application's build validation phase, as the container boundary for bean discovery becomes inconsistent.

### 16.8 Assertion and Assumption Standards
- New modules MUST use `org.junit.jupiter.api.Assertions` and `org.junit.jupiter.api.Assumptions` for all tests.
- **AssertJ is NOT used** in this project. Do not import or use `assertThat`.
- This ensures consistency and reduces classpath bloat in the final native binary.

### 16.9 beans.xml Schema Mapping
- New modules MUST use the Jakarta EE 10 (version 4.0) schema for `src/main/resources/META-INF/beans.xml` to ensure consistency with `amsha-core`.
- Example header: `<beans xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0" bean-discovery-mode="annotated">`

### 16.10 ToolResponse API
- `ToolResponse` is a Java **Record**. Use canonical accessors:
  - `success()` (instead of `isSuccess()`)
  - `exitCode()` (instead of `status()`)
  - `output()` (instead of `content()`)

### 16.11 Bootstrap and Setup Integration (§16.11 INVARIANT)
- New modules with required or optional user settings (API keys, toggles, binary paths) MUST be integrated into the interactive setup wizard in `BinaryBootstrap`.
- Update `com.amsha.app.util.CliUtils.getMissingVarSpecs()` in `amsha-app` to include the `VarSpec` for these variables.
- If the module requires binary dependencies (e.g., Chromium, specialized tools), implement an idempotent `*Installer` utility that:
  - Uses `ProcessBuilder.inheritIO()` to stream progress to the user's terminal.
  - Prefers `mvnw exec:java` if in a source tree, falling back to `java -cp` for binary distributions.
  - Detects existing binaries to avoid redundant downloads.
- **Interactive UI:** All bootstrap prompts MUST use `readLineWithFallback(System.console(), prompt)` to support environment-redirected terminals (e.g., `mise`, `podman exec`).
- **Dependency Constraint:** Per §16.1, the new module MUST NOT depend on `amsha-app`. The integration logic is owned by `amsha-app` in `BinaryBootstrap`.


## 17. General Java Guards

- Null-check `e.getMessage()` before use - several JVM exception types return `null`.
  Use: `e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()`. Prefer `String.format(...)` / `log.infof(...)` over concatenation in log statements. `MDC` values removed in `finally` blocks only - never remove in `catch`.

## 18. REST Status Code Conventions

| Operation | Endpoint pattern | Expected status |
|---|---|---|
| Create session | `POST /api/v1/sessions` | `201` |
| Run agent (sync) | `POST /api/v1/sessions/{id}/run` | `200` |
| Approve | `POST /api/v1/sessions/{id}/approve` | `200` |
| Onboarding Status | `GET /onboarding/status` | `200` |
| Advance step | `PUT /api/v1/onboarding/drafts/{draftId}/steps/{stepId}` | `200` |
| Commit draft | `POST /api/v1/onboarding/drafts/{draftId}/commit` | `200` (`409` if not at review) |
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
- [ ] Is `JdbiRetryWrapper` only called from a non-transactional or JDBI-managed transaction context (§5.14)
- [ ] After storage extraction: is retry logic encapsulated inside `amsha-persistence-sqlite`? No `PersistenceRetryPort` in core. (§5.14.1)
- [ ] After storage extraction: are `NOT_SUPPORTED` annotations still on `CheckpointApprovalService`, NOT moved to adapters? (§5.10.1)
- [ ] Does any new `CheckpointStatus` terminal value need to be added to `doMarkCheckpointsFailedRequiresNew`? (§5.18)
- [ ] Does any new checkpoint query include `ORDER BY c.id DESC`? (§5.19)
- [ ] Does `AsyncPersistenceEngine` still bind `WorkspaceScopeHolder` per event, not per thread? (§5.17)
- [ ] Is `SessionReaper.findAwaitingApprovalIds()` still `NOT_SUPPORTED`? (§5.16)
- [ ] Is `SessionReaper.markApprovalTimedOut()` still `NOT_SUPPORTED`? (§5.16)

## 20. Routing and Classification Consolidation

### 20.1 Implementation Proximity
- Interface implementations for core SPIs (e.g. IntentDetector) SHOULD reside in the same module as their primary injection points (e.g. IntentClassificationHandler in amsha-core).
- **Rationale:** Placing the only implementation of a required core bean in a downstream module (like amsha-app) frequently triggers jakarta.enterprise.inject.UnsatisfiedResolutionException during the validation phase of the upstream module's build, especially in native-image compilation.
- Consolidated IntentDetectorModelProducer, SkillAwareIntentDetector, and their associated LlmModelFactory into amsha-core to ensure reliable CDI resolution and build stability.

### 20.2 LLM Provider Dependencies
- When moving routing logic into amsha-core, all required LLM provider extensions (e.g. quarkus-langchain4j-anthropic) MUST also be added to amsha-core.
- Failure to do so leads to ClassNotFoundException or unresolved imports in the routing factories (e.g. ClaudeCodeProviderFactory).
- T1-2 boundary is an abstraction seam within `amsha-core`, not a Maven module split. `LangChain4jLlmAdapter` MUST remain in `amsha-core` per §20.1.

---

## 21. UI Architecture Invariants

### 21.1 Technology Stack
- MUST use **Lit** (Web Components) for UI structure and logic.
- MUST use **Vite** as the build tool and dev server.
- MUST use **TypeScript** in `strict` mode for all frontend code.

### 21.2 Component Communication & State
- MUST use **`@lit/context`** for sharing application-wide state (e.g., `amshaSessionId`, user profile) between nested components.
- Direct `window` object state-sharing or global mutable stores SHOULD be avoided in favor of the context provider pattern.

### 21.3 Security & Sanitization
- Every dynamic HTML fragment rendered from LLM or agent output MUST be sanitized with **`dompurify`** before insertion into the DOM.
- Markdown rendering (via `marked`) MUST be piped through the sanitizer to prevent XSS from unsafe agent outputs.

### 21.4 Backend Communication
- All data-plane interaction MUST flow through the **REST API** (`/api/v1`) or **WebSockets (`/ws/chat`)**.
- The UI MUST handle standard error codes (401 Unauthorized, 404 Not Found, 500 Server Error) gracefully, providing clear feedback to the user.

### 21.5 Styling & Aesthetics
- MUST use **Vanilla CSS** with CSS Custom Properties (Variables) for theming and layout.
- MUST NOT introduce heavy utility frameworks (Tailwind, Bootstrap) unless explicitly requested and approved as a project-wide standard.
- Aim for a premium, responsive design with smooth transitions and micro-animations.

### 21.6 Packaging & Deployment
- The UI build process MUST output to `ui/dist/`.
- The `mise build:ui-copy` task is the authoritative mechanism for moving the bundled UI into the `amsha-app` `META-INF/resources` directory for production packaging.
- Do NOT commit the `dist/` folder to version control.

### 21.7 Accessibility (a11y)
- UI components MUST FF WAI-ARIA standards (labels, roles, current-page markers). 
- Interactive elements (buttons, inputs) MUST have descriptive `aria-label` attributes if they do not contain text.

### 21.8 Resource Lifecycle
- Components establishing long-lived connections (WebSockets, SSE) MUST explicitly close/clean them up in `disconnectedCallback()` to prevent memory leaks and "ghost" sessions.

### 21.9 UI Secret Handling
- Sensitive credentials (API keys) MUST NOT be passed in URL query parameters where HTTP headers are supported (e.g. REST, SSE); prefer `X-AMSHA-API-KEY` or secure Cookies.
- **Architectural Trade-off**: The use of **`localStorage`** for API keys is explicitly allowed to support persistent developer sessions across browser restarts/tab closures. This is an intentional choice for productivity given Amsha Agent's primary usage as a local developer tool.
- Non-secret transient data SHOULD prefer `sessionStorage`.

### 21.10 MCP Tenant Isolation
- External MCP calls MUST operate within isolated sessions linked to specific client IDs. Hardware sharing or default/synthetic session identifiers (like "mcp-external") are strictly prohibited.
- Role assignment for MCP requests MUST dynamically reflect capability headers to preserve least-privilege principles, rather than defaulting to max-privilege roles like `COORDINATOR`.
- Any external request tunneling into the workspace via the bridge MUST generate unique audit telemetry tying the payload execution to the specific source client and its requested capabilities, completely avoiding budget crossover or blind file-access scenarios across concurrent clients.

---

## 22. JDK Feature Policy (CRITICAL)

ONLY use JDK features FULLY FINALIZED (non-preview) in their respective release.

FORBIDDEN - preview or unfinalized:
- ❌ StructuredTaskScope / Structured Concurrency (still preview in JDK 25)
- ❌ Any feature requiring `--enable-preview` compiler flag
- ❌ value classes / primitive types (preview in JDK 25)

Flexible constructor bodies are finalized in JDK 25 and may be used without
preview flags.

Do NOT add `--enable-preview` to any `pom.xml` compiler or surefire configuration.
`StructuredTaskScope` is FORBIDDEN. Use `ManagedExecutor` + virtual threads instead.
Every new or modified class MUST compile cleanly under JDK 25 standard without preview flags.

---

## 23. Frontend (Lit/Web Component) Invariants

### 23.1 Shadow DOM Event Boundary

Custom events dispatched from inside a Lit component's Shadow DOM MUST use `window.dispatchEvent()`. Events with `bubbles: true; composed: true` do NOT reliably cross the Shadow DOM boundary in all cases (observed with Chrome + Lit 3.x). This affects any event the shell needs to receive from child components.

**Pattern:**
```typescript
// CORRECT - always works
window.dispatchEvent(new CustomEvent('session-ready', {
  detail: { sessionId }
}));

// WRONG - may be trapped in Shadow DOM
this.dispatchEvent(new CustomEvent('session-ready', {
  detail: { sessionId },
  bubbles: true,
  composed: true
}));
```

Affected events (MUST use `window.dispatchEvent`):
- `session-ready` (chat → shell, sessionId handoff)
- `ws-status-change` (WsManager → shell, WebSocket status updates)

### 23.2 CSS Height Resolution in Flex Layouts

Percentage height (`height: 100%`) on a child element does NOT resolve reliably when the parent is a flex item with `overflow-y: auto`. The flex item's computed height is not considered "definite" for percentage resolution in all browsers. This manifests as blank content areas.

**Fix:**
```css
/* Parent (flex item with scroll): */
.content-area {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

/* Child component: use flex: 1 instead of height: 100% */
:host {
  display: flex;
  flex-direction: column;
  flex: 1;          /* ← NOT height: 100% */
  min-height: 0;
}
```

### 23.3 Scrollbar Styles in Shadow DOM

`::-webkit-scrollbar` pseudo-element styles defined in the document's `<style>` block do NOT apply to scrollable elements inside Shadow DOMs. Each component with a scrollable container MUST define its own scrollbar styles.

```css
.scrollable-container::-webkit-scrollbar { width: 6px; }
.scrollable-container::-webkit-scrollbar-track { background: transparent; }
.scrollable-container::-webkit-scrollbar-thumb {
  background: var(--border);
  border-radius: 999px;
}
```

Components with internal scrollable containers that need explicit scrollbar styles:
- `arc-chat-interface.ts`: `.messages`
- `arc-audit-panel.ts`: `.table-wrap`, `.drawer`
- `arc-scheduler-panel.ts`: `.drawer`

### 23.4 Dynamic Tag Names in Templates

Using `<${route.component}>` syntax (dynamic tag names from array iteration) can silently fail if the component is not yet registered as a custom element at render time. This produces a blank page with no console error.

**Fix:** Use explicit conditional rendering instead:
```typescript
// CORRECT
${this._route === '#/chat' ? html`<arc-chat-interface class="content-area"></arc-chat-interface>` : nothing}

// WRONG - may fail silently
${ROUTES.map(r => html`<${r.component} class="content-area"></${r.component}>`)}
```

### 23.5 `updated()` Lifecycle Guard for Async Connections

When a Lit component's `updated()` lifecycle triggers an async connection (e.g., WebSocket), the guard condition MUST check for a property that is available on the **first render**, not a property that is only set after a subsequent async operation.

**Pattern:**
```typescript
// CORRECT - platformId is available immediately
updated(changedProperties) {
  if (this._appContext?.platformId && !this._ws && !this._connecting) {
    this._connect();
  }
}

// WRONG - sessionId is empty on first render (set later by WebSocket handshake)
updated(changedProperties) {
  if (this._appContext?.sessionId && ...) {  // never true on initial load
    this._connect();
  }
}
```

### 23.6 WebSocket Stale Connection Guard

When reconnecting a WebSocket, the old connection's `onclose` handler may fire AFTER the new connection is established. This handler must check that it is still the current manager before reacting.

```typescript
const manager = new WsManager(url, onFrame, (status) => {
  if (this._ws !== manager) return;  // stale connection - ignore
  if (status === 'closed') {
    this._ws = null;
    this._scheduleReconnect();
  }
});
this._ws = manager;
this._ws.connect();
```

Always call `this._ws.disconnect()` before nullifying the old connection in `reconnect()`. Otherwise the old WebSocket stays alive and its `session_terminated` frame may disconnect the new connection.

---

## 24. Threading & Concurrency Invariants

### 24.1 Virtual Threads + `synchronized` on JDK 25

JDK 25 has resolved the virtual-thread pinning issue with `synchronized` blocks. You do NOT need to migrate `synchronized` to `ReentrantLock` for Loom compatibility. Keep `synchronized` where it is semantically correct.

The existing `ReentrantLock` usages in `VaultKeyCache` and `SecretVaultImpl` are there for CDI proxy correctness (`synchronized (this)` on a proxied bean locks the proxy, not the instance), NOT for virtual thread pinning. Keep these as-is.

**Rule:**
- `synchronized` blocks → safe on JDK 25, prefer for simplicity
- `ReentrantLock` → only when CDI proxying requires it (field-level lock on `@ApplicationScoped` beans)

### 24.2 Halt/Cancellation Interruption Checks

Tool execution loops MUST check `Thread.currentThread().isInterrupted()` and `WorkspaceManager.isSessionHalted(sessionId)` at loop boundaries. These are GA APIs (no preview features) and are the primary halt propagation mechanism.

Required check points (checked at loop top before each iteration):
- `StepDecisionRouter.route()` - `while (true)` routing loop
- `ResumeStrategyDispatcher.resumeToolExecution()` - `while (true)` retry loop
- `ParallelToolStepExecutor.executeParallel()` - before each `future.get()` in the for loop

```java
if (Thread.currentThread().isInterrupted() || WorkspaceManager.isSessionHalted(sessionId)) {
    log.warnf("session %s: halted during %s", sessionId, "operation");
    // exit cleanly or cancel remaining futures
}
```

### 24.3 Safe Agentic Workflow (SAW) Orchestration

To maintain a secure, cost-effective, and resilient multi-agent execution pipeline, developers MUST adhere to the following SAW orchestration invariants:

1. **Lightweight Task Classification Routing**:
   - Requests resolved via `ModelRouter.resolve` MUST be pre-screened using `TaskClassifierService`.
   - Lightweight tasks (such as chitchat, simple greetings, or session controls like `/reset`) MUST bypass expensive model configuration overrides (such as DeepSeek or Cerebras) and route directly to the global low-cost provider (`infinite-loop`), minimizing token expenses and rate limit exhaustion.
2. **Evidence-Based Coder Verification**:
   - Code outputs produced by the `coder` (Brahma) role MUST pass programmatically through `SandboxVerificationGate` checks (running code blocks inside `SandboxExecTool`).
   - *HITL Bypass Exception*: Since code verification is an automated, sandboxed validator check executed by the system harness, the verification gate's invocation of `SandboxExecTool.execute()` directly is an intentional bypass of normal Human-in-the-Loop (HITL) checks. This enables automated validation of code correctness before presenting it to the user.
   - If execution fails (non-zero exit code or compilation error), the system MUST intercept the failure, extract the error details, and automatically delegate the stack trace recursively to the `debugger` (Shiva) to auto-fix and heal before returning the final response.
3. **Complex Task Backlog Refinement**:
   - Coordinator tasks spanning multiple domains MUST be refined at startup by the `AgentStateMachineRunner`. The goal MUST be split into discrete sub-tasks and populated into the workspace filesystem as active `agent_task` markdown logs to support strict accountability.

### 24.4 Dynamic SkillsJars (Agentic Skills) Execution Invariant

Dynamic skills (loaded dynamically from `.jar` files in classpath) are **instruction-based wrappers** that inject system prompts and permitted tools to the LLM reasoning loop. They do not contain compiled runtime execution engines themselves.

**Invariants:**
- **External CLI Binary Dependency**: When a dynamic skill (like `remote-browser`) declares an allowed tool capability (like `sandbox_exec`), the LLM is expected to call external CLI binaries (e.g. `browser-use`) inside the sandbox environment.
- **Environment Provisioning**: The runtime execution sandbox (such as the `amsha-tool-runner` Podman container) or the host machine (in fallback mode) MUST pre-install any external binary/CLI tools referenced by the dynamic skill. If the binaries are absent in the sandbox or on the host PATH, tool execution will fail with `command not found`.
- **Pre-execution Verification**: Dynamic skills must be documented with their system dependencies, and verification gates (like `browser-use doctor`) must be run during bootstrap or sanity testing.
- **Native SkillsJar Index Parity**: Every SkillsJar dependency added to or removed from `app/pom.xml` MUST have its exact path below `META-INF/skills/` added to or removed from `app/src/main/resources/META-INF/skills/skills-index.txt` in the same change. Run `python3 ./scripts/verify_skillsjar_index.py --fix` to regenerate it and `python3 ./scripts/verify_skillsjar_index.py` to validate it. GraalVM Native Image cannot enumerate classpath resource directories, so native mode loads these skills through the index; `META-INF/skills/**` MUST remain in `quarkus.native.resources.includes`.

### 24.5 SkillsJars Cloud Dependency Vetting

Skills that intentionally create HTML/browser artifacts MUST declare `browser-preview: true` in `SKILL.md` frontmatter. They MAY declare an optional skill-home-relative `template-dir` (such as `tmpl`) when starter templates exist. The provider and `host_exec` consume this metadata generically for output/template guidance, new-artifact enforcement, and browser opening; browser behavior MUST NOT be inferred from skill names or incidental prompt text.

Before adding a SkillsJar dependency to `app/pom.xml`, the skill's `SKILL.md` instructions **MUST** be reviewed to determine whether the skill requires:

1. **Cloud API keys / SaaS authentication** (e.g. `browser-use auth login`, `BROWSER_USE_API_KEY`)
2. **Network egress to third-party services** (e.g. Browser Use Cloud, external browser farms)
3. **Paid service subscriptions**

**Invariants:**
- **Cloud-only skills MUST NOT be bundled** unless the corresponding API key is provisioned in `.env` and injected into the sandbox via `SandboxSecretResolver`. A skill that silently fails without an API key wastes LLM tokens on infinite auto-heal loops.
- **Skill vetting checklist**: Before adding a SkillsJar, extract and read the `SKILL.md` from the JAR (`jar xf <jar> META-INF/skills/.../SKILL.md`) and verify:
  - Does it work offline / in an air-gapped sandbox?
  - Does it require authentication or API keys?
  - Does it need network access beyond the sandbox?
  - Are all CLI dependencies installable in the `Dockerfile.toolrunner`?
  - Has its exact `SKILL.md` path been added to `app/src/main/resources/META-INF/skills/skills-index.txt` for the native image?
- **Automated admission**: `python3 ./scripts/verify_skillsjar_index.py` rejects indexed dependencies that require unsupported agent tools or advertise artifact generation without a one-shot `scripts/` entry point. Runtime loading applies the same compatibility rules and quarantines rejected skills before role registration or capability grants. Inspect runtime results through `GET /skills/admission`.
- **No implicit execution capability**: SkillsJars that declare no valid tool capability remain prompt-only. The loader MUST NOT infer or grant `host_exec` merely because the source is a SkillsJar.
- **Auto-heal loop prevention**: If a skill's CLI tool fails with an authentication or configuration error (not a code error), `ExecutionFailureRecoveryService` should NOT trigger Shiva auto-heal — the error is environmental, not fixable by code changes.

**Why**: The `browser-use` skill (removed in P105) appeared to be a local CLI tool but actually required a paid Browser Use Cloud API key. Without it, the LLM entered an infinite Shiva auto-heal loop (`heal:shiva:...:heal:shiva:...`) that grew session IDs until macOS rejected the workspace directory path with `File name too long`.
