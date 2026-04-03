# PROMPT — PHASE 84-A: P0 Invariants & Zero‑Day Correctness (Tier A)

Role: Senior Java Architect, Bug Fixer & Senior Java Developer  
Task: Phase 84-A — Fix 6 confirmed correctness defects in live production code.  
All bugs below exist today. No architectural changes. Surgical fixes only.

***

## Architecture Context and Other References

- Core invariants, transaction rules, and HITL lifecycle are defined in `Architecture_Invariants.md` (especially §§3.4, 5.8, 5.9, 5.16, 7.4).  
- All fixes must keep ArcClaw’s SQLite single-writer discipline and HITL semantics intact.

***

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. Every fix must be accompanied by at least one test that proves the bug existed and now does not (red → green).
2. No new dependencies. No changes to Maven module structure or package layout.
3. All existing tests (≈1000+) must remain green after each component is completed.
4. Any new regex must use a `static final Pattern` field; never compile patterns inline.
5. All changes must compile and run on JDK 25 without using preview features.
6. Use `mvn clean test` (or `mvn clean verify` for module scope) in every affected module to avoid stale classes.

***

### Component A1 — Tx / GC: `SessionReaper.markApprovalTimedOut` (`F1/F4`)

**Goal:** Restore the “outer NOT_SUPPORTED → inner REQUIRES_NEW” transaction pattern for GC paths to satisfy §5.8 and §5.16. 

**Bug:**  
- `SessionReaper.markApprovalTimedOut` is annotated `@Transactional(REQUIRED)` and calls:
  - `StatefulAgentStoreImpl.updateBatchToolDecision` (REQUIRES_NEW via inner `doUpdateBatchToolDecision`), and  
  - `WorkspaceManager.terminateSession` → `doMarkCheckpointsFailedRequiresNew` (REQUIRES_NEW),  
  creating a REQUIRED → REQUIRES_NEW → REQUIRES_NEW stack on SQLite’s single writer. 

**Actions:**

1. Change `markApprovalTimedOut` (and any sibling GC entry points doing similar cascades) to `@Transactional(TxType.NOT_SUPPORTED)`.
2. Verify that inner writers (`doMarkCheckpointsFailedRequiresNew`, `doUpdateBatchToolDecision`) remain `REQUIRES_NEW` and that no new outer `REQUIRED` annotations are introduced.
3. Add tests:
   - A regression test that simulates a timed-out approval and asserts:
     - No `SQLITE_BUSY` is thrown when `markApprovalTimedOut` runs concurrently with normal workload (can be approximated with a mock or a synthetic concurrent scenario).
     - The checkpoint status and failure reason are correctly updated and the session is terminated per invariants.
   - A test that asserts all GC entry methods (`sweepTimedOutApprovals`, `markApprovalTimedOut`, related helpers) are either unannotated or explicitly `NOT_SUPPORTED`.

***

### Component A2 — Tx: Double `REQUIRES_NEW` on `updateBatchToolDecision` (`F2`)

**Goal:** Ensure there is exactly one transactional write boundary per batch tool decision update, as required by §5.8. 

**Bug:**  
- `StatefulAgentStoreImpl.updateBatchToolDecision` is annotated `@Transactional(REQUIRES_NEW)` and internally calls `doUpdateBatchToolDecision`, which is also `@Transactional(REQUIRES_NEW)`.  
- When invoked from a `REQUIRED` context (e.g. old `markApprovalTimedOut`), this creates a 3‑layer transaction stack: REQUIRED → REQUIRES_NEW (interface) → REQUIRES_NEW (impl). 

**Actions:**

1. Remove `@Transactional(REQUIRES_NEW)` from the override method `updateBatchToolDecision`.
2. Keep `@Transactional(REQUIRES_NEW)` only on `doUpdateBatchToolDecision` (or equivalent inner method) as the single writer boundary.
3. Add tests:
   - A test that calls `updateBatchToolDecision` from a synthetic `@Transactional(REQUIRED)` wrapper and verifies:
     - Exactly one DB write occurs for the decision.
     - No nested transaction warnings or `SQLITE_BUSY` are emitted.
   - An ArchUnit or reflection-based test that asserts:
     - No public interface methods in `StatefulAgentStore` and its impls are annotated `REQUIRES_NEW` when they delegate to another `REQUIRES_NEW` method.

***

### Component A3 — HITL Outcome Semantics: `ApprovalStateMachine.decide` (`F3`)

**Goal:** Align approval outcome semantics with actual resume behavior for mixed APPROVE/DENY batches. 

**Bug:**  
- When all tools in a parallel batch have decisions recorded, `ApprovalStateMachine.decide()` currently does:  
  `outcome = (decision == APPROVE) ? APPROVED : REJECTED;`  
  i.e., outcome is based only on the last tool’s decision, while `resumeParallelBatch` still executes all tools marked APPROVED in `batchToolDecisions`. This yields `REJECTED` outcomes even when some tools run successfully. 

**Actions:**

1. In `ApprovalStateMachine.decide`, after confirming `allDecided == true`, compute the outcome from the **full decision map**:
   - Load the existing `batchToolDecisions` JSON into a map.
   - Overlay the current `decisionStr` for `toolName`.
   - If any tool is `APPROVED`, set `ApprovalOutcome.APPROVED`.
   - If all tools are `DENIED`, set `ApprovalOutcome.REJECTED`.
   - (Optional) If you want a separate mixed state, keep current `PARTIAL_STILL_PENDING` only for truly pending batches.
2. Avoid extra DB round-trips: merge the current write into the in-memory map rather than re-reading the checkpoint.
3. Add tests:
   - A test with a batch `[github, schedule]` where:
     - First decision: APPROVE github.
     - Second decision: DENY schedule.
     - Assert `ApprovalOutcome.APPROVED` and that `resumeParallelBatch` executes only `github`.
   - A test where all tools are DENIED and outcome is `REJECTED`.

***

### Component A4 — REST `/deny` correctness: `AgentSessionService.runAsync` (`F16`)

**Goal:** Ensure REST `/deny` follows the same HITL lifecycle as other channels and preserves SSE and batch state invariants. 

**Bug:**  
- `runAsync`’s `/deny` branch:
  - Loads the checkpoint directly via `agentStore.load`,
  - Sets status to FAILED and saves it,
  - Returns an HTTP response,  
  **bypassing** `CheckpointApprovalService` and `ApprovalStateMachine`.  
- Consequences:
  - `batchToolDecisions` stay all PENDING.
  - No SSE `HitlEvent(DENIED)` is emitted.
  - No `ApprovalDecisionEvent` is fired.
  - No proper resume/terminate logic (session can linger in heartbeat tracker). 

**Actions:**

1. Remove the direct mutation path in `runAsync` for the `/deny` case.
2. Route all denials through `CheckpointApprovalService`:
   - For parallel batches: `denyAll(sessionId, "Human denied via REST")` → `triggerResume(...)`.
   - For single-tool checkpoints (if reachable via this endpoint): `denyTool(...)` → `triggerResume(...)`.
3. Ensure `runAsync`’s deny branch:
   - Calls the appropriate `CheckpointApprovalService` method,
   - Calls `triggerResume` only after the decision write commits (to respect NOT_SUPPORTED + REQUIRES_NEW pattern),
   - Returns a lightweight acknowledgement (e.g., 200/202) while the actual agent result goes over SSE/Telegram/web channel.
4. Add tests:
   - A test that simulates a parallel HITL pause, then calls REST `/runAsync` with goal `deny`:
     - Asserts `batchToolDecisions` are all `DENIED`,
     - SSE stream receives a DENIED event,
     - The session is either correctly terminated or resumed and then terminated per invariants.
   - A test that ensures `/deny` no longer directly mutates checkpoint status outside the approval machinery.

***

### Component A5 — REST `/approve` correctness: `AgentSessionService.runAsync` (`F17`)

**Goal:** Make REST approvals respect per-tool decisions and share the same gateway as Telegram HITL. 

**Bug:**  
- `runAsync`’s `/approve` branch currently:
  - Sets `context.setResumeSignal(true)`,
  - Calls `agentLoop.run` directly.  
- `batchToolDecisions` remain all PENDING, so `resumeParallelBatch` executes every tool, ignoring per-tool DENIED decisions already captured via other channels. 

**Actions:**

1. Remove direct `agentLoop.run` usage in the `/approve` branch for HITL resumes.
2. Route REST approvals through `CheckpointApprovalService`:
   - For parallel batches: `approveAll(sessionId, "Human approved via REST")` → `triggerResume(...)`.
   - For single-tool flows: `approveTool(...)` → `triggerResume(...)`.
3. Ensure this path:
   - Writes decisions via `updateBatchToolDecision` before any resume,
   - Calls `ApprovalStateMachine.decide` via `CheckpointApprovalService`,
   - Uses `resumeAfterBatchDecision` for actual resumption of the agent loop.
4. Add tests:
   - A mixed batch where one tool is DENIED via `/hitl` and then the user calls REST `/approve`:
     - Assert that only APPROVED tools execute.
     - Assert that `batchToolDecisions` reflect the per-tool decisions and the outcome matches `ApprovalStateMachine`.
   - A test verifying that `runAsync` no longer has an `agentLoop.run` fast-path for `/approve`.

***

### Component A6 — MCP STDIO transport: Concurrency & Dead Process Handling (`F18/F19`)

**Goal:** Make MCP STDIO transport safe under parallel tool execution and resilient to server crashes. 

**Bug 1 (F18):**  
- `McpClientService.sendRequestStdio` writes to a shared `OutputStream` and reads from a shared `BufferedReader` per server without synchronization. Parallel tool calls to the same server can interleave bytes and misroute responses. 

**Bug 2 (F19):**  
- When `reader.readLine()` returns `null`, the dead process is never removed from `processes`, `processWriters`, `processReaders`. Retries keep hitting a dead pipe and no reconnect is attempted. 

**Actions:**

1. Introduce per-server IO serialization:
   - Either a `ConcurrentHashMap<String, ReentrantLock>` and `lock.lock()/unlock()` around the full send/receive section, OR
   - A dedicated reader loop per server with a request ID → `CompletableFuture` map for responses.
   - Keep the implementation minimal and focused; no new frameworks or reactive stacks.
2. On EOF (`readLine() == null`):
   - Clean up all state for that server (`processes`, `processWriters`, `processReaders`, any locks/futures).
   - Attempt a reconnect via `connectStdio(serverName)` inside the existing retry loop.
   - If reconnect fails after retries, surface a clear exception.
3. Add tests (prefer module-level integration style with a fake STDIO server):
   - A test with two parallel tool calls to the same fake server:
     - Assert no interleaving/corruption, each caller gets its own JSON-RPC response.
   - A test where the fake server closes STDIO mid-call:
     - Assert that the client tears down state, attempts reconnect on the next call, and ultimately either recovers or fails with a clear error after exhausting retries.

***

# PROMPT — PHASE 84-B: P1 Runtime Correctness & Tool Safety (Tier B)

Role: Senior Java Architect, Bug Fixer & Senior Java Developer  
Task: Phase 84-B — Fix 6 confirmed correctness and safety issues in core runtime behavior.  
All bugs below exist today. No architectural rewrites. Targeted changes only.

***

## Architecture Context and Other References

- Continue to honor `Architecture_Invariants.md`, especially channel neutrality (§7.2), checkpoint ordering (§5.19), and HITL governance (§3.4). 

***

## IMPLEMENTATION REQUIREMENTS

(Same as Phase 84-A Common Requirements.)

***

### Component B1 — Channel-Type Correctness for Cron HITL (`F5`)

**Goal:** Make cron HITL resumes route to the correct channel instead of always REST. 

**Bug:**  
- `CronJobRunner` constructs `ChannelUser` with a hardcoded `ChannelType.RESTAPI` for cron jobs, even when the originating user/channel was Telegram or another adapter. 

**Actions:**

1. Persist the originating `channelType` in `AgentCronEntity` (or equivalent cron metadata).
2. In `CronJobRunner`, use the stored `channelType` when constructing `ChannelUser`.
3. Ensure `ApprovalCheckpointManager.persistBatch` and `CheckpointResumeHandler` correctly propagate and respect this channel type.
4. Add tests:
   - A cron-triggered HITL pause created from a Telegram session:
     - Assert that the resume notification and HITL flows use Telegram, not REST.
   - A REST-origin cron job:
     - Assert that REST remains the channel type.

***

### Component B2 — Checkpoint Ordering Invariant (`F6`)

**Goal:** Ensure “latest checkpoint” queries are stable and conform to §5.19. 

**Bug:**  
- `StatefulAgentStoreImpl.findLatestAwaitingApprovalForCron` (and potentially similar methods) use `ORDER BY updatedAt DESC`, while the invariant requires `ORDER BY c.id DESC` for “latest checkpoint” semantics. 

**Actions:**

1. Change affected JPQL queries to `ORDER BY c.id DESC` for “latest” semantics.
2. Search for all “latest” or “most recent” checkpoint queries and align them with `ORDER BY c.id DESC`.
3. Add tests:
   - A test that inserts multiple checkpoints with manipulated `updatedAt` ordering but increasing IDs:
     - Assert that `findLatestAwaitingApprovalForCron` returns the highest-ID checkpoint.
   - Optionally an ArchUnit-style test that fails if any checkpoint query uses `ORDER BY updatedAt` for “latest”.

***

### Component B3 — MCP Dynamic Tools & HITL (`F10`)

**Goal:** Ensure dynamic MCP tools still go through HITL and role checks; allowlist does not bypass governance. 

**Bug:**  
- `ToolInvocationGuard` checks `isDynamicToolAllowed(toolName)` and returns `Allow` immediately, skipping subsequent HITL/role evaluation. 

**Actions:**

1. Refactor `ToolInvocationGuard` so that:
   - `isDynamicToolAllowed` only answers “can this tool be considered at all?”
   - HITL and role-schema checks still run afterward for allowed dynamic tools.
2. Ensure destructive MCP tools still respect `requiresApprovalForTools` and per-tool manual approval flags.
3. Add tests:
   - A dynamic tool that would normally require HITL:
     - Assert that the decision still goes through HITL when autonomy level or policy requires it.
   - A dynamic tool not requiring HITL:
     - Assert it is allowed, but still subject to capability/role checks.

***

### Component B4 — Non-Sandbox Tool Param Safety (`F23`)

**Goal:** Ensure all tools (sandbox and non-sandbox) receive null-free parameter maps. 

**Bug:**  
- `ToolRegistry.invokeTool` strips null values only for sandbox tools, leaving direct tools (e.g. `ManageScheduleTool`, `MemorySearchTool`) exposed to nulls from schema validators. 

**Actions:**

1. Move the null-stripping step to a common pre-invocation stage:
   - e.g. build a `sanitizedParams` map for **all** tools before branching into sandbox vs direct execution.
2. Ensure sandbox and direct paths both use `sanitizedParams`.
3. Add tests:
   - A test for a direct tool where input JSON includes a field with `null`:
     - Assert that `execute()` receives a map without that key or with a non-null replacement, depending on policy.
   - A regression test for sandbox tools to ensure behavior is unchanged (still null-free).

***

### Component B5 — Compaction Summary & FTS Coupling (`F20`)

**Goal:** Prevent transient FTS attach failures from rolling back main compaction writes. 

**Bug:**  
- `CompactionSummaryRepository.upsertSummary` wraps:
  - Main table write, and
  - FTS shadow table maintenance (DELETE/INSERT into `fts.compactionsummaryfts`)  
  in a single `@Transactional` scope. If FTS attach fails, the main write is rolled back. 

**Actions:**

1. Keep the main table write in the transactional method.
2. Wrap the FTS maintenance queries in a `try/catch` inside that method:
   - On failure, log a WARN with enough metadata (sessionId, userId) to debug.
   - Do **not** roll back the main write if FTS fails.
3. Add tests:
   - A test where the FTS table is deliberately made unavailable (e.g., stubbed EntityManager or throwing native query) while main writes succeed:
     - Assert that the main table is updated.
     - Assert a WARN log is produced.
   - A test where everything works showing both main and FTS tables updated as before.

***

### Component B6 — REST `approve()` Iteration Ceiling (`F21`)

**Goal:** Prevent unbounded token consumption when resuming long-running sessions via REST `approve()`. 

**Bug:**  
- `AgentSessionService.approve()` reads `checkpoint.getMaxIterations()` and uses `Math.max(3, storedMax)`, but does not account for iterations already consumed. Users who set a high `maxIterations` can get essentially a full fresh budget on resume. 

**Actions:**

1. Decide and implement a sane ceiling strategy, e.g.:
   - Track `iterationsUsed` on checkpoint (if available) or infer it.
   - Compute `remaining = storedMax - iterationsUsed`, clamp with a configurable upper bound (e.g., `arcclaw.approval.max-remaining-iterations`).
2. Use `remaining` as the resume iteration budget instead of `storedMax` alone.
3. Add tests:
   - A scenario with `maxIterations = 20` and 18 already used:
     - Assert that resume gets a small remaining budget (e.g., 2 or a configured cap).
   - A scenario with small `maxIterations` (e.g., 3) verifying current floor behavior is preserved.

***

# PROMPT — PHASE 84-C: P2 Hot‑Path, Races & Resilience (Tier C)

Role: Senior Java Architect, Bug Fixer & Senior Java Developer  
Task: Phase 84-C — Optimize hot paths, fix concurrency races, and improve resilience for high-load scenarios.  
All bugs below exist today but are not immediate P0/P1 correctness blockers.

***

## IMPLEMENTATION REQUIREMENTS

(Same as Phase 84-A Common Requirements.)

***

### Component C1 — Agent Loop Hot Path (`F9`, `F22`, `F28`)

**Goal:** Reduce unnecessary DB reads, rely on structured flags instead of string heuristics, and keep synthesizer output clean. 

**Actions:**

1. `AgentLoop.tryDelegateFinishFastPath` (`F9`):
   - Replace string-prefix guards with checks against `ToolResponse.success` or equivalent structured metadata in `ReasoningStep`.
2. `AgentStateMachineRunner` hot-path load (`F22`):
   - Cache checkpoint status in `AgentExecutionContext`.
   - Only re-read from `agentStore` when transitioning into or out of approval states, or on specific suspicious conditions.
3. Synthesizer error pollution (`F28`):
   - Filter observations with `success=false` or prefixed with `"Tool execution failed"` from the synthesizer’s `combinedContext`.
4. Add tests:
   - Unit tests for `tryDelegateFinishFastPath` verifying correct dispatch based on success flags.
   - Tests for reduced DB reads (e.g., using a mock `StatefulAgentStore` and counting interactions).
   - A synthesizer test where error observations exist but do not appear in the final synthesized answer context.

***

### Component C2 — Workspace Lifecycle Races & Scheduler Resilience (`F12`, `F14`, `F13`)

**Goal:** Avoid destroying workspace scopes mid-flight, prevent semaphore leaks, and bound Telegram callback impact. 

**Actions:**

1. `WorkspaceManager.terminateSession` / GC (`F12`):
   - Before destroying a session’s workspace scope during timeout sweeps, check `markBatchResuming` or active session markers.
   - Skip termination for sessions currently being resumed or actively running.
2. `AgentCronScheduler` semaphore leak (`F14`):
   - Wrap `vThreads.execute` in a try/catch; release the semaphore in the `catch (RejectedExecutionException)` path.
3. `TelegramCallbackHandler.answerCallback` timeout (`F13`):
   - Add `.timeout(Duration.ofSeconds(5))` (configurable) to the HTTP request builder before `sendAsync`.
4. Add tests:
   - A test that simulates concurrent resume and GC, asserting that `WorkspaceScoped` beans are not destroyed while active.
   - A test that mocks `ManagedExecutor` to throw `RejectedExecutionException`, asserting that semaphore permits are not leaked.
   - A test that ensures Telegram callbacks do not hang indefinitely and respect the configured timeout.

***

### Component C3 — Parallel Tool / Ollama CallId Stability (`F8`)

**Goal:** Guarantee stable `callId` pairing between AI messages and tool results in parallel batches for Ollama. 

**Actions:**

1. Verify and enforce that `ReasoningStepParser` applies the UUID fallback for null `callId` **at parse time**, not at resume time.
2. Ensure `ChatHistoryAssembler` never sees null `callId` and maintains the required sequence:
   - One `AiMessage(req1..reqN)` followed by N `ToolExecutionResultMessage`s in callId order.
3. Add tests:
   - A synthetic Ollama batch with some tools lacking `callId`:
     - Assert that parser assigns stable UUIDs.
     - Assert that `ChatHistoryAssembler` reconstructs history correctly without errors.

***

# PROMPT — PHASE 84-D: P3 Tradeoffs, Multi‑Tenant Readiness & Cleanup (Tier D)

Role: Senior Java Architect, Bug Fixer & Senior Java Developer  
Task: Phase 84-D — Address documented tradeoffs, multi-tenant readiness, and cleanup items.  
These are important but can follow after Tier A–C are stable.

***

## IMPLEMENTATION REQUIREMENTS

(Same as Phase 84-A Common Requirements.)

***

### Component D1 — Known Tradeoffs & Deployment Behavior (`F7`, `F11`, `F24`)

**Actions:**

1. Double-resume guard (`F7`):
   - Document and, if desired, introduce a persisted marker or external coordination for cross-restart “exactly-once” behavior.
2. Zombie recovery vs canary deployments (`F11`):
   - Add a `SHUTTING_DOWN` or grace-period mechanism before marking `IN_PROGRESS` checkpoints as FAILED on startup.
3. `isGeneratedCodeBlock` false positives (`F24`):
   - Refine the regex and/or gate it by role (e.g., stricter only for “executor” roles) using `static final Pattern`.

***

### Component D2 — Compaction / RAG Multi-Tenant & Scoring (`F15`, `F25`, `F26`)

**Actions:**

1. Compaction & HITL interaction (`F15`):
   - Skip or defer compaction when a session has an AWAITING_APPROVAL checkpoint; or protect key turns from being compacted.
2. Two-tier memory scoring cleanup (`F25`):
   - Either remove the unused synthetic score field or explicitly document that it is intentionally unused to prevent misuse.
3. Multi-tenant BM25 IDF pollution (`F26`):
   - Design and (when you flip to SaaS/multi-tenant) implement per-tenant FTS shadow tables or a content table keyed by user, and adjust MATCH queries accordingly.

***

### Component D3 — Additional Multi-Tenant & UX Hardening

- Audit for any remaining cross-tenant leaks beyond `HitlService.getPendingApprovals` (already handled in Tier A).
- Improve logs, metrics, and documentation so future maintainers can see when invariants are enforced vs when they were historically documented only.
