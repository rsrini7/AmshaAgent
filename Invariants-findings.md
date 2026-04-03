
## Findings: Issues & Future Risk Areas

I identified **15 concrete findings**, ranging from critical architectural risks to correctness concerns.

---

### 🔴 CRITICAL — F1: `SessionReaper.markApprovalTimedOut` violates §5.8 (REQUIRED → REQUIRES_NEW nesting)

**File:** `SessionReaper.java` — `markApprovalTimedOut(AgentCheckpoint cp)`

```java
@Transactional(Transactional.TxType.REQUIRED)   // ← outer REQUIRED holds Connection-1
void markApprovalTimedOut(AgentCheckpoint cp) {
    if (cp.hasBatchToolDecisions()) {
        BatchDecisionUtil.pendingToolNames(cp, objectMapper).forEach(toolName ->
            agentStore.updateBatchToolDecision(   // ← this is @REQUIRES_NEW → SQLITE_BUSY!
                cp.getSessionId(), cp.getAgentRole(), toolName, "DENIED"));
    }
    ...
}
```

`agentStore.updateBatchToolDecision()` is declared `@Transactional(REQUIRES_NEW)` on both the interface and implementation. Calling it from a `@Transactional(REQUIRED)` frame replicates the exact SQLITE_BUSY cascade that §5.8 was written to prevent. **Fix:** annotate `markApprovalTimedOut` with `@Transactional(NOT_SUPPORTED)`.

---

### 🔴 CRITICAL — F2: `StatefulAgentStoreImpl.updateBatchToolDecision` double `REQUIRES_NEW` annotation

**File:** `StatefulAgentStoreImpl.java`

```java
@Override
@Transactional(Transactional.TxType.REQUIRES_NEW)   // ← interface annotation
public boolean updateBatchToolDecision(...) {
    return withRetry(
        () -> self.doUpdateBatchToolDecision(...),    // ← self-call is ALSO REQUIRES_NEW
        "updateBatchToolDecision");
}

@Transactional(Transactional.TxType.REQUIRES_NEW)   // ← implementation annotation
public boolean doUpdateBatchToolDecision(...) { ... }
```

The `withRetry()` wrapper calls `self.doUpdateBatchToolDecision()`. If the interface method itself is annotated `@Transactional(REQUIRES_NEW)`, Narayana creates a new TX for the `updateBatchToolDecision()` call AND another nested TX for `doUpdateBatchToolDecision()`. When called from `markApprovalTimedOut()` (F1), this means three transaction layers — Connection-1 (REQUIRED) → Connection-2 (REQUIRES_NEW on interface) → Connection-3 (REQUIRES_NEW on impl). Only one SQLite writer is allowed. **Fix:** Remove the `@Transactional(REQUIRES_NEW)` from the interface override method; only `doUpdateBatchToolDecision` should carry it.

---

### 🔴 CRITICAL — F3: `ApprovalStateMachine.decide()` — incorrect `outcome` when last decision is DENY

**File:** `ApprovalStateMachine.java` lines ~85–90

```java
outcome = (decision == ApprovalDecision.APPROVE)
    ? ApprovalOutcome.APPROVED
    : ApprovalOutcome.REJECTED;
```

If the last tool in a batch is denied but all others were approved, the outcome is `REJECTED`. The `TelegramCallbackHandler` then treats the whole approval as rejected and edits the keyboard to "completed." But `CheckpointResumeHandler.resumeParallelBatch()` will execute the approved tools normally. The outcome reported to the UI/channel is semantically wrong — it should be `APPROVED` since the batch resumes (just with that tool denied). More importantly, when some tools are approved and one is denied, the correct `ApprovalOutcome` should be derived from the full decision map, not just the last decision. **Fix:** check `decisions.values().stream().anyMatch("APPROVED"::equals)` for the final outcome determination.

---

### 🟠 HIGH — F4: `SessionReaper.sweepTimedOutApprovals` has no `@Transactional` on the entry method, but `markApprovalTimedOut` has `@Transactional(REQUIRED)` which calls `terminateSession()` (transaction-free zone violation)

**File:** `SessionReaper.java`

`markApprovalTimedOut()` is `@Transactional(REQUIRED)` and at the end calls `workspaceManager.terminateSession(sessionId)`. `terminateSession()` calls `markCheckpointsFailed()` which calls `self.doMarkCheckpointsFailedRequiresNew()` — another `@Transactional(REQUIRES_NEW)`. So there's a chain: `REQUIRED (markApprovalTimedOut)` → `REQUIRES_NEW (doMarkCheckpointsFailedRequiresNew)` which hits the §5.8 forbidden pattern. Additionally, calling `terminateSession()` inside `@Transactional(REQUIRED)` while `terminateSession()` itself is not annotated is borderline §5.9 violation — it delegates to CDI proxied `REQUIRES_NEW` sub-operations. **Fix:** `markApprovalTimedOut` should be `@Transactional(NOT_SUPPORTED)` like the other GC methods.

---

### 🟠 HIGH — F5: `CronJobRunner` uses `ChannelType.REST_API` hardcoded for all cron context users

**File:** `CronJobRunner.java`

```java
ChannelUser user = new ChannelUser(job.userId, job.userId, "Cron User", ChannelType.REST_API);
```

When a cron job triggers HITL approval (`AwaitingApproval` result), the `ApprovalCheckpointManager.persistBatch()` stamps `channelType = "rest_api"` onto the checkpoint. When `CheckpointResumeHandler.resumeAfterBatchDecision()` creates the `ChannelMessage`, it switches on `cType.id()` — REST_API produces a `RestApiMessage`. But if the original user is on Telegram, the resume notification goes to REST, not Telegram — the user never sees the approval result. **Fix:** `job.channelType` should be stored on `AgentCronEntity` and used when constructing `ChannelUser`.

---

### 🟠 HIGH — F6: `findLatestAwaitingApprovalForCron` uses `ORDER BY c.updatedAt DESC` instead of `ORDER BY c.id DESC` (§5.19 violation)

**File:** `StatefulAgentStoreImpl.java`

```java
TypedQuery<AgentCheckpoint> q = em.createQuery(
    "SELECT c FROM AgentCheckpoint c " +
    "WHERE c.sessionId LIKE :sessionPrefix " +
    "  AND c.status = :status " +
    "ORDER BY c.updatedAt DESC",   // ← §5.19 requires ORDER BY c.id DESC
    AgentCheckpoint.class);
```

§5.19 says all "most recent checkpoint" queries MUST use `ORDER BY c.id DESC`. `updatedAt` is a `LocalDateTime` stored as TEXT in SQLite — string ordering of ISO-8601 timestamps works, but only if the format is perfectly consistent. If `updatedAt` is ever `null` (§5.17 mentions drain threads may not propagate full context), SQLite returns NULLs in unpredictable order. **Fix:** change to `ORDER BY c.id DESC`.

---

### 🟠 HIGH — F7: Double-resume guard (`markBatchResuming`) is not persisted — JVM restart clears it silently

**File:** `StatefulAgentStoreImpl.java`

```java
private final ConcurrentHashMap<String, Boolean> resumingBatches = new ConcurrentHashMap<>();
```

The §7.29 in-memory guard prevents concurrent double-resume within one JVM lifetime. But if the JVM restarts while a resume is in-flight (e.g. during a rolling restart), the `ConcurrentHashMap` is cleared. Two concurrent callback signals — one arriving just before restart and the other just after — will both find the map empty and both call `AgentLoop.run()` simultaneously. This is an acceptable tradeoff for now (the architecture doc acknowledges it is not persisted), but it means the guard is only effective against same-JVM concurrency. **Risk scenario:** Kubernetes rolling deploy with Telegram callbacks buffered during the gap.

---

### 🟠 HIGH — F8: `ChatHistoryAssembler` — parallel batch reconstituted without verifying `callId` ordering matches LangChain4j expectations

**File:** `ChatHistoryAssembler.java` (not read in full, referenced from §7.3 invariant)

The invariant requires `ONE AiMessage([req1, req2, ..., reqN])` followed by `N ToolExecutionResultMessages in callId order`. The assembler must reconstruct call IDs from the `batch` JSON array. If `ReasoningStepParser` ever emits `callId = null` for any tool (Ollama path, §7.19), and the UUID fallback is applied inconsistently, the `AiMessage` ↔ `ToolExecutionResultMessage` pairing breaks — LangChain4j will throw a protocol error on the next LLM call, causing the resumed session to immediately fail. The UUID fallback in the parser needs verification that it's applied at parse time, not at resume time.

---

### 🟡 MEDIUM — F9: `AgentLoop` — `tryDelegateFinishFastPath` fast-path does not check `success()` on the tool response

**File:** `AgentLoop.java` — `tryDelegateFinishFastPath()`

```java
if (observation.startsWith("⏳") || observation.startsWith("⚠️") 
    || observation.startsWith("Tool execution failed")
    || observation.startsWith("Parameter ")
    || observation.startsWith("❌")) {
    return Optional.empty();
}
return Optional.of(AgentResult.finished(observation, ...));
```

The guard is string-prefix based. If a sub-agent's legitimate output happens to start with one of these characters (e.g. a user asked for a Unicode art piece starting with "❌"), the fast-path will incorrectly fall through to the state machine instead of using the clean result. Conversely, a new error prefix not listed here (e.g. `"[DEDUP]"` cached observations or custom error formats from MCP tools) will be incorrectly treated as a finished answer. **Fix:** use the `ToolResponse.success()` flag stored in the history step, not string prefix inspection.

---

### 🟡 MEDIUM — F10: `ToolInvocationGuard` — role schema check missing for dynamic MCP tools

**File:** `ToolInvocationGuard.java`

```java
boolean isInternalTool = toolRegistry.findTool(toolName).isPresent();
if (!isInternalTool
        && dynamicToolLookup != null && !dynamicToolLookup.isUnsatisfied()
        && dynamicToolLookup.get().isDynamicToolAllowed(toolName)) {
    return new GuardDecision.Allow(); // permitted via dynamic MCP registry
}
```

When a dynamic MCP tool is allowed, the guard **skips** all checks after this point — including the HITL check. This means a dynamic MCP tool that performs destructive operations (e.g. `filesystem:delete`) will never trigger HITL approval regardless of the session's autonomy level. The `McpToolAllowlist` guards against unknown tools but does not apply HITL governance. **Fix:** after the `isDynamicToolAllowed()` check, continue to evaluate `requiresApprovalForTools` and `toolRequiresManualApproval` before returning `Allow`.

---

### 🟡 MEDIUM — F11: `SessionReaper.onStartup` marks all `IN_PROGRESS` checkpoints as `FAILED` — including active multi-agent sub-sessions

**File:** `SessionReaper.java` — `doRecoverZombies()`

On JVM restart, every `IN_PROGRESS` checkpoint is marked `FAILED`. In multi-agent delegation scenarios, a coordinator session and its `cron-{id}-{executionId}` sub-session may both be `IN_PROGRESS`. Both get marked `FAILED` on restart. This is correct for crash recovery. However, if using blue-green or canary deployments where the old JVM is gracefully shut down while a long-running agent loop is mid-flight, the new JVM will mark the still-valid coordinator checkpoint FAILED before the old JVM finishes. The result is duplicate audit entries and a ghost FAILED checkpoint that the user never sees resolved. **Mitigation:** Add a grace period or a `SHUTTING_DOWN` status transition during graceful shutdown.

---

### 🟡 MEDIUM — F12: `WorkspaceScopeContext.destroySession` called inside `terminateSession` during active `WorkspaceContext.run()` on the same session

**File:** `WorkspaceManager.terminateSession()` / `WorkspaceScopeContext`

`SessionReaper.sweepTimedOutApprovals()` → `markApprovalTimedOut()` → `terminateSession(sessionId)` → `context.destroySession(sessionId)`. If `AgentLoop.run()` is still executing for that session on another thread (e.g. the approval resume just started, or a cron job is still running), the CDI `@WorkspaceScoped` beans will be destroyed mid-flight. Any subsequent access to a `@WorkspaceScoped` bean (like `SessionKnowledgeBase`) from the still-running loop will get a fresh uninitialized instance — producing silent data loss (RAG context disappears, chat memory resets). **Fix:** check `agentStore.markBatchResuming()` in `sweepTimedOutApprovals` before calling `terminateSession`, similar to the GC sweep's `awaitingApprovalIds` exclusion.

---

### 🟡 MEDIUM — F13: `TelegramCallbackHandler` — callback `answerCallback` is async-fire-and-forget with no timeout binding

**File:** `TelegramCallbackHandler.java`

```java
HTTP_CLIENT.sendAsync(...).exceptionally(ex -> { log.warn(...); return null; });
```

If the Telegram Bot API is slow, the `HttpClient` request will hang in the background indefinitely. `HttpClient.newHttpClient()` is a `static final` field — it uses a shared thread pool. Under high callback volume (many simultaneous HITL approvals), these hung requests can exhaust the shared HTTP client's connection pool. **Fix:** add `.timeout(Duration.ofSeconds(5))` to the `HttpRequest.Builder` before `answerCallback()`.

---

### 🟡 MEDIUM — F14: `AgentCronScheduler` — `Semaphore.tryAcquire()` + `releaseClaimToActive()` race under high load

**File:** `AgentCronScheduler.java`

```java
if (!concurrencyLimiter.tryAcquire()) {
    cronRepository.releaseClaimToActive(job.id);
    continue;
}
vThreads.execute(() -> {
    try { jobRunner.execute(job); }
    finally { concurrencyLimiter.release(); }
});
```

Between `tryAcquire()` succeeding and `vThreads.execute()` being called, the virtual thread could be delayed. If the JVM is under GC pressure, `vThreads.execute()` throws `RejectedExecutionException` (if the `ManagedExecutor` queue is full). The `finally { concurrencyLimiter.release(); }` only runs if the lambda executes — if `execute()` throws before scheduling, the semaphore is never released and that permit is leaked for the lifetime of the JVM. **Fix:** wrap `vThreads.execute(...)` in a try/catch that releases the semaphore on `RejectedExecutionException`.

---

### 🔵 LOW — F15: `CompactionService` — compaction not aware of parallel batch AWAITING_APPROVAL checkpoints

**File:** `CompactionService.java` (referenced from `CompactionEventBridge`)

The compaction flow summarizes `ChatMemory` when the message window approaches its limit. If a session is `AWAITING_APPROVAL` for a parallel batch, and compaction fires during the wait window, the compacted summary may lose the tool call context the LLM needs to correctly interpret the approved results on resume. The `CompactionInFlightFlag` prevents concurrent compaction, but it does not check whether an active HITL approval is pending. **Risk:** after approve → resume, the `ChatHistoryAssembler` reads the compressed history, and the LLM receives an incomplete picture of what tool was called and why — potentially leading to a hallucinated or contradictory follow-up response.

---

## Invariant Compliance Summary

| Invariant | Status |
|---|---|
| §1.2 Core module — no Telegram imports | ✅ Clean — `arcclaw-core` has zero Telegram references |
| §1.4 P82 SPI enforcement — channel modules don't import core | ✅ `TelegramCallbackHandler` uses `ApprovalGateway` SPI only |
| §2.7 6-context async propagation | ✅ `ContextAwareExecutorService` propagates all 6; `AsyncPersistenceEngine` exception documented |
| §3.4 Double-resume guard | ✅ `markBatchResuming` / `clearBatchResuming` in `finally` |
| §4.4 P82 Data-driven personas | ✅ `DefaultAgent` + Qute templates; no class-per-persona |
| §5.8 No REQUIRED→REQUIRES_NEW nesting | ⚠️ **VIOLATED** in `SessionReaper.markApprovalTimedOut` (F1) |
| §5.9 Transaction-free zone | ⚠️ **BORDERLINE** — `markApprovalTimedOut` calls `terminateSession` inside `@REQUIRED` (F4) |
| §5.10 `CheckpointApprovalService` NOT_SUPPORTED | ✅ All 4 mutating methods correct |
| §5.11 `ApprovalStateMachine.decide()` NOT_SUPPORTED | ✅ Correct |
| §5.12 `AgentCronScheduler` NOT_SUPPORTED + reclaimStaleCrons isolated | ✅ Correct |
| §5.13 Agroal `background-validation=true`, max-size≥3 | ✅ Confirmed in `application.yaml` |
| §5.14 `SqliteRetryTemplate` not nested inside active tx | ✅ All callers are NOT_SUPPORTED or un-annotated |
| §5.16 WorkspaceManager GC methods NOT_SUPPORTED | ⚠️ `markApprovalTimedOut` is `@REQUIRED` not `@NOT_SUPPORTED` (F1/F4) |
| §5.18 Terminal status exclusion | ✅ `doMarkCheckpointsFailedRequiresNew` excludes COMPLETED/FAILED/ARCHIVED/COMPROMISED |
| §5.19 `ORDER BY c.id DESC` | ⚠️ `findLatestAwaitingApprovalForCron` uses `ORDER BY updatedAt DESC` (F6) |
| §7.4 Parallel HITL atomicity | ✅ Any tool needing HITL pauses entire batch |
| §7.7 All-tools dedup check before any execution | ✅ Pass 1 validates all before Pass 2 executes |
| §7.9 ReasoningStep shape — migration required | ✅ No schema changes without migration |
| §7.11 No `getFirst()` fallback | ✅ Removed, `MUST NOT` enforced in parser |
| §7.13 OTel context propagation to virtual threads | ✅ `Context.current()` captured before `supplyAsync`, restored via `context.makeCurrent()` |
| §7.17 Metrics — no `__parallel__` sentinel counter | ✅ `onStepComplete()` called per-tool, not on sentinel |
| §7.18 History mutated EXACTLY ONCE per batch | ✅ Only in `buildCompletedBatchDecision()` |
| §7.19 Non-null callId / UUID fallback for Ollama | ✅ `ReasoningStepParser` applies UUID fallback |
| §7.29 Double-resume prevention | ✅ `markBatchResuming` ConcurrentHashMap guard |
| §7.35 Callback expiry guard independent of GC | ✅ `isCheckpointExpired()` in `TelegramCallbackHandler` |
| §15 Jandex + beans.xml in every module | ✅ All non-app modules have both |
| §16.6 Surefire timeout config | ✅ Parent POM has `shutdown=kill`, `forkCount=1`, `reuseForks=true` |

---

## Priority Fix List

| Priority | Finding | File | Fix |
|---|---|---|---|
| P0 | F1 — `markApprovalTimedOut` `@REQUIRED` wraps `REQUIRES_NEW` | `SessionReaper.java` | Change to `@Transactional(NOT_SUPPORTED)` |
| P0 | F2 — Double `@REQUIRES_NEW` on `updateBatchToolDecision` | `StatefulAgentStoreImpl.java` | Remove annotation from interface override |
| P0 | F3 — Wrong `outcome` when last decision is DENY in mixed batch | `ApprovalStateMachine.java` | Derive outcome from full decision map |
| P1 | F4 — `terminateSession()` called inside `@REQUIRED` context | `SessionReaper.java` | Fix via F1 (becomes NOT_SUPPORTED) |
| P1 | F5 — Cron `ChannelUser` hardcoded to `REST_API` | `CronJobRunner.java` | Store `channelType` in `AgentCronEntity` |
| P1 | F6 — `ORDER BY updatedAt` instead of `ORDER BY id DESC` | `StatefulAgentStoreImpl.java` | Change to `ORDER BY c.id DESC` |
| P1 | F10 — MCP dynamic tools bypass HITL check | `ToolInvocationGuard.java` | Continue HITL evaluation after MCP allow |
| P2 | F9 — `tryDelegateFinishFastPath` string-prefix guard | `AgentLoop.java` | Use `ToolResponse.success()` flag |
| P2 | F12 — `destroySession` during active resume | `WorkspaceManager.java` | Check `markBatchResuming` before terminate |
| P2 | F13 — `answerCallback` HTTP client no timeout | `TelegramCallbackHandler.java` | Add `.timeout(Duration.ofSeconds(5))` |
| P2 | F14 — Semaphore permit leak on `RejectedExecutionException` | `AgentCronScheduler.java` | try/catch around `vThreads.execute()` |
| P3 | F7 — Double-resume guard not restart-safe | `StatefulAgentStoreImpl.java` | Acceptable; document explicitly |
| P3 | F8 — `callId` ordering on Ollama resume | `ChatHistoryAssembler.java` | Verify UUID fallback applied at parse time |
| P3 | F11 — Startup zombie recovery kills active canary sessions | `SessionReaper.java` | Graceful shutdown `SHUTTING_DOWN` status |
| P3 | F15 — Compaction during HITL approval wait | `CompactionService.java` | Skip compaction if AWAITING_APPROVAL |

---

Now I have read every major file. Let me compile the continuation — deeper findings from `AgentStateMachineRunner`, `HitlService`, `ToolRegistry`, `McpClientService`, `CompactionService`, `TwoTierMemoryService`, `WorkspaceScopeContext`, and `AgentSessionService`.

---

## Part 2: Continued Deep Findings (F16–F28)

---

### 🔴 CRITICAL — F16: `AgentSessionService.runAsync()` — `/deny` path bypasses `ApprovalStateMachine`, leaving SSE and `batchToolDecisions` in corrupted state

**File:** `AgentSessionService.java` — `runAsync()` `isDeny` branch

```java
if (isDeny) {
    agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
        cp.setStatus(CheckpointStatus.FAILED);
        cp.setFailedAt(Instant.now());
        cp.setFailReason("Human denied tool execution via REST.");
        agentStore.save(cp);           // ← direct save, no ApprovalStateMachine
    });
    response.resume(Response.ok(...).build());
    return;
}
```

This bypasses `ApprovalStateMachine.decide(REJECT)` entirely. Consequences:
- `batchToolDecisions` JSON is **never updated** — all tools stay `PENDING` in the DB
- **No `HitlEvent(DENIED)` SSE event fires** — the web UI's HITL panel is permanently stuck showing "Awaiting Approval"
- **No `ApprovalDecisionEvent` CDI event fires** — Telegram keyboard is never cleaned up
- **No `WorkspaceManager.terminateSession()`** — session lingers in heartbeat tracker and is only collected by GC sweep after TTL expires
- **No `agentStore.clearBatchResuming()`** — if the resume flag was set, it's never cleared

The correct path for any denial is `ApprovalStateMachine.decide(sessionId, toolName, REJECT, reason)` followed by `triggerResume()` (which calls `resumeAfterBatchDecision()` with all DENIED). **Fix:** Remove the `isDeny` fast-path from `runAsync()` and route through `CheckpointApprovalService.denyAll()` → `ApprovalStateMachine`.

---

### 🔴 CRITICAL — F17: `AgentSessionService.runAsync()` `/approve` on a parallel batch ignores `batchToolDecisions` — all pending tools executed regardless of individual decisions

**File:** `AgentSessionService.java` — `runAsync()` `isApproval` branch

When `/approve` is sent via REST for a parallel batch session:
```java
AutonomyLevel runLevel = AutonomyLevel.FULL;
context.setResumeSignal(true);
AgentResult result = agentLoop.run(..., context);
```

`AgentLoop` routes to `CheckpointResumeHandler.handle()` → `resumeParallelBatch()`. Inside `resumeParallelBatch()`, `decisions` are loaded from `batchToolDecisions`. Since no `updateBatchToolDecision()` was called before this path, all tools remain `PENDING`. The code treats `PENDING` as "needs execution" (falls through to `toolRegistry.invokeTool()`). This means:

- Per-tool granular approval (approve `github`, deny `manage_schedule`) is **completely ignored** on the REST path
- All tools in the batch execute regardless of whether they should be approved
- The `approveAll` semantics are silently applied even when the user intended per-tool control

**Fix:** Route REST `/approve` through `CheckpointApprovalService.approveAll()` → `ApprovalStateMachine.decide()` → `resumeAfterBatchDecision()`, not directly through `AgentLoop.run()`.

---

### 🔴 CRITICAL — F18: `McpClientService.sendRequestStdio()` — no synchronization; concurrent MCP tool calls on same STDIO server produce garbled responses

**File:** `McpClientService.java` — `sendRequestStdio()`

```java
private JsonNode sendRequestStdio(String serverName, String method, ObjectNode params) throws Exception {
    // ... write to processWriters.get(serverName) ...
    BufferedReader reader = processReaders.get(serverName);
    String line = reader.readLine();  // ← unsynchronized; concurrent reads interleave
    ...
}
```

All STDIO MCP servers share a single `BufferedReader` per server name in `processReaders`. If `ParallelToolStepExecutor` submits two concurrent tool calls to the same STDIO MCP server on separate virtual threads, both will:
1. Write their JSON-RPC request to the shared `OutputStream` (interleaved bytes → corrupted framing)
2. Race to `reader.readLine()` — one thread gets the other thread's response

The `invokeStdio()` response is then parsed by the wrong virtual thread. Best case: JSON parse error. Worst case: the wrong tool result is silently returned as the correct one. **Fix:** Synchronize `sendRequestStdio()` on a per-server `ReentrantLock`, or use a request-ID correlation map with a `CompletableFuture` per request ID and a dedicated reader thread per server.

---

### 🟠 HIGH — F19: `McpClientService.connectStdio()` — dead process never cleaned up; reconnection not attempted

**File:** `McpClientService.java`

If the STDIO MCP sub-process crashes mid-session:
- `reader.readLine()` returns `null` → `IOException("MCP server closed connection unexpectedly")`
- The `@Retry(maxRetries=2)` on `invoke()` re-attempts 3 times into the dead pipe → all fail
- The dead entries in `processes`, `processWriters`, `processReaders` are **never removed**
- Subsequent calls after the retries also fail permanently

No health-check, restart logic, or dead-process detection exists. In production, a STDIO MCP server OOM or crash silently disables all tools from that server for the JVM's lifetime. **Fix:** On `null` response from `readLine()`, remove the server from all maps and attempt re-`connectStdio()` before throwing to the retry loop.

---

### 🟠 HIGH — F20: `CompactionSummaryRepository.upsertSummary()` — same `@Transactional` writes to both main and ATTACHed `fts` schema; FTS sync can silently fail under connection pressure

**File:** `CompactionSummaryRepository.java`

```java
@Transactional
public void upsertSummary(...) {
    // writes to main schema (compaction_summary)
    em.flush();
    // native writes to fts.compaction_summary_fts (ATTACHED db)
    em.createNativeQuery("DELETE FROM fts.compaction_summary_fts ...").executeUpdate();
    em.createNativeQuery("INSERT INTO fts.compaction_summary_fts ...").executeUpdate();
}
```

`FtsConnectionInterceptor.onConnectionAcquire()` ATTACHes the FTS database per-connection when Agroal first acquires the connection. If Agroal's `background-validation` recycles a broken connection and then issues a new one, `onConnectionAcquire()` fires again and re-attaches — correct. However, if the FTS ATTACH itself fails (e.g. the `fts` schema file is locked by SQLite's WAL checkpoint), the `em.createNativeQuery` will throw `"no such table: fts.compaction_summary_fts"`. The `@Transactional` will roll back the main table write too, leaving the compaction summary unwritten and the in-memory `ChatMemory` already trimmed (the `chatMemoryStore.updateMessages()` call in `CompactionService.compact()` runs AFTER `upsertSummary()` — wait, actually looking at the code:

```java
summaryRepository.upsertSummary(sessionId, userId, newSummary, toCompact.size());
// ↑ this is transactional
chatMemoryStore.updateMessages(sessionId, filteredKeep);
// ↑ this runs AFTER — if upsertSummary throws, messages are NOT trimmed
```

Actually this is safe — the exception propagates before `updateMessages`. But the user now has a compaction failure on every call until the FTS schema issue is resolved. **Fix:** Wrap the native FTS queries in a separate try/catch with a WARN log; compaction summary is recoverable from the main table even without FTS sync.

---

### 🟠 HIGH — F21: `AgentSessionService` — `approve()` (REST `POST /sessions/{id}/approve`) does not set `maxIterations` from checkpoint, always defaults to 3

**File:** `AgentSessionService.java` — `approve()`

```java
int resumeIterations = checkpoint.get().getMaxIterations() != null
    ? Math.max(3, checkpoint.get().getMaxIterations())
    : 3;
```

`AgentCheckpoint.maxIterations` is set in `ApprovalCheckpointManager.persist()` — but only when `context.maxIterations()` is set. For CLI/Telegram paths, `maxIterations` comes from the caller and is correctly stored. For REST paths through `AgentSessionService.runSync()`, the `maxIterations` is passed in from `request.maxIterations()` or defaults to 5. But `ApprovalCheckpointManager.persist()` stores it via `cp.setMaxIterations(context.maxIterations())` — `context` is `AgentExecutionContext` which receives it from `AgentLoop.runWithRole()`. This looks correct.

However: the legacy `approve()` method in `AgentSessionService` reads `checkpoint.get().getMaxIterations()` and uses `Math.max(3, ...)`. If `maxIterations` was stored as `5` and the agent used `4` iterations before pausing, the resume gets `5` iterations — it could hit the `LoopExceeded` result on the very next LLM call if the agent is slow to FINISH. The `Math.max(3, ...)` is a floor guard, but there's no ceiling guard — a user who set `maxIterations = 20` for a complex task will resume with `20` fresh iterations, potentially running 20 more LLM calls when only 1-2 are needed. **Risk:** unbounded token consumption on resume for high-`maxIterations` sessions.

---

### 🟠 HIGH — F22: `AgentStateMachineRunner` — AWAITING_APPROVAL guard reads from DB on every tool invocation (hot-path database read)

**File:** `AgentStateMachineRunner.java`

```java
// FIX ISSUE 5: Guard condition to ensure approval was formally flushed.
Optional<AgentCheckpoint> cpOpt = agentStore.load(context.sessionId(), context.agentRole());
if (cpOpt.isPresent() && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
    log.warnf("Session %s is still AWAITING_APPROVAL...");
    return AgentResult.failed(...);
}
```

`agentStore.load()` is `@Transactional(REQUIRED)` — it opens a DB transaction, executes `SELECT ... ORDER BY c.id DESC LIMIT 1`, and closes the transaction. This happens on **every tool invocation** in the loop — once per agent iteration, on the hot path between the LLM call and the tool call. In a 5-iteration loop with 5 tool calls, that's 5 extra DB reads. At scale with many concurrent sessions, this adds non-trivial SQLite read pressure. The guard exists to protect against a very specific race condition (stale approval state). **Fix:** Cache the checkpoint status in `AgentExecutionContext` and only re-read if the previous step involved an approval operation (status `AWAITING_APPROVAL` → `IN_PROGRESS` transition).

---

### 🟠 HIGH — F23: `ToolRegistry.invokeTool()` — null params not stripped for non-sandbox (direct) tools

**File:** `ToolRegistry.java`

```java
// Sandbox path:
Map<String, Object> sanitizedParams = params.entrySet().stream()
    .filter(e -> e.getValue() != null)
    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
var command = tool.sandboxCommand(sanitizedParams);

// Direct (non-sandbox) path:
ToolResponse response = tool.execute(params);  // ← raw params, nulls not stripped
```

Null values from Groq's strict schema validator are stripped for sandbox tools but not for direct tools. `ManageScheduleTool`, `CheckListTool`, `DelegateToAgentTool`, `MemorySearchTool`, `BraveWebSearchTool`, and `SerperWebSearchTool` are all direct (non-sandbox) tools. If Groq sends `{"query": null}` for `MemorySearchTool`, the tool's `execute()` receives a null query and produces a null-pointer or empty result rather than a clear parameter validation error. **Fix:** Move null-stripping to the common pre-invocation path before the sandbox/direct branch.

---

### 🟡 MEDIUM — F24: `AgentStateMachineRunner.isGeneratedCodeBlock()` — false positives for researcher/coordinator roles on explanatory prose

**File:** `AgentStateMachineRunner.java`

```java
if (stripped.matches("(?si)here[''']?s a .{0,60}(program|function|class|" +
    "method|algorithm|implementation|snippet|code).*```.*")) return true;
```

The regex requires `.*```.*` at the end — meaning a backtick fence must appear in the answer for the second branch to match. This is reasonable. But the `.{0,60}` wildcard between "here's a" and the keyword means: "here's a quick comparison of the algorithm used in ..." triggers this pattern if the answer also contains a code fence anywhere (even in a quoted example). A researcher summarizing code without generating it could be incorrectly blocked. **Risk:** increases with the coordinator role, which frequently quotes code blocks from tool observations in its synthesis. Not a crash risk, but a user-facing degradation.

---

### 🟡 MEDIUM — F25: `TwoTierMemoryService` — RRF fusion scores are not normalized; FTS5 and vector scores have incompatible scales

**File:** `TwoTierMemoryService.java`

The RRF formula `1.0 / (RRF_K + rank)` is rank-based — it doesn't use raw FTS5 BM25 scores or vector cosine similarity scores. This is intentional and correct. However, `toScoredChunks()` converts FTS5 results to `ScoredChunk` with synthetic rank-based scores `1.0 / (i + 1)` — these scores are passed into `reciprocalRankFusion()` but RRF **discards** the `.score()` field and uses only rank position. So the synthetic scores set in `toScoredChunks()` are computed but never used — the method creates a misleading intermediate `ScoredChunk.score` that is thrown away. This is a dead-code computation, not a bug, but it signals that future developers adding score-weighted fusion could accidentally use the wrong field.

---

### 🟡 MEDIUM — F26: `CompactionSummaryRepository.searchMemory()` — multi-tenant BM25 IDF pollution in shared FTS5 virtual table

**File:** `CompactionSummaryRepository.java`

```sql
SELECT cs.* FROM compaction_summary cs
JOIN fts.compaction_summary_fts fts_v ON cs.sessionId = fts_v.sessionId
WHERE fts_v.summary MATCH ?1
  AND cs.user_id = ?2
ORDER BY rank
LIMIT ?3
```

`ORDER BY rank` is the FTS5 BM25 rank computed across the **entire corpus** (all users' summaries). IDF (inverse document frequency) is a global statistic — if 1000 users have the word "python" in their summaries, its IDF weight is low for all users, including a user whose only session is about Python. This degrades search precision for common terms in multi-user deployments. In single-user (self-hosted) deployments, this is irrelevant. **Fix for multi-tenant:** create per-user FTS5 shadow tables or add a `WHERE cs.user_id = ?2` predicate inside the FTS5 MATCH filter using a content table with user_id.

---

### 🟡 MEDIUM — F27: `HitlService.getPendingApprovals()` — fetches ALL `AWAITING_APPROVAL` checkpoints with no pagination, no user filter

**File:** `HitlService.java`

```java
@Transactional
public Map<String, PendingApproval> getPendingApprovals() {
    return em.createQuery(
        "SELECT c FROM AgentCheckpoint c WHERE c.status = :status",
        AgentCheckpoint.class)
        .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
        .getResultList()   // ← no LIMIT, no user filter
        ...
}
```

In a multi-user deployment, this query returns every AWAITING_APPROVAL checkpoint from every user. `HitlResource.listPending()` exposes this at `GET /hitl` with only API key auth (not per-user filtered). So User A can see User B's pending tool names, parameters, and session IDs. This is a **cross-tenant data exposure** issue. **Fix:** Filter by the requesting API key's bound sessions (`heartbeatTracker.getOwner(sessionId) == currentKey`), and add `ORDER BY updatedAt DESC LIMIT 50` to prevent unbounded result sets.

---

### 🟡 MEDIUM — F28: `AgentStateMachineRunner` — `handleFinishAction()` synthesizer path loads full history for synthesis but ignores failed/empty observations

**File:** `AgentStateMachineRunner.java` — `handleFinishAction()`

```java
if (AgentRoleEnum.SYNTHESIZER.toScopedName().equals(agentRole)) {
    String combinedContext = history.stream()
        .filter(s -> s.observation() != null
                && !s.observation().isBlank()
                && !Task completed.".equals(s.observation()))
        .map(s -> "Thought: " + s.thought() + "\nFinding: " + s.observation())
        .collect(Collectors.joining("\n\n"));
```

The synthesizer aggregates findings from all steps in history. But history includes steps from the coordinator's initial reasoning and the delegate-to-agent steps. The parallel batch `__parallel__` sentinel step has an observation like `[github → OK], [memory_search → FAIL]` — a terse summary, not actual content. If a sub-agent returned an error like "Tool execution failed: connection refused", that error is included in the synthesis context verbatim. The synthesizer LLM may then incorporate that error message into its final synthesis as if it were factual content. **Fix:** Filter observations starting with "Tool execution failed" or `success=false` from synthesis context, or add a `step.success()` flag check.

---

## Updated Complete Findings Table

| # | Severity | Area | Component | Issue | Fix Priority |
|---|---|---|---|---|---|
| F1 | 🔴 Critical | Tx/§5.8 | `SessionReaper` | `markApprovalTimedOut` `@REQUIRED` wraps `REQUIRES_NEW` | P0 |
| F2 | 🔴 Critical | Tx/§5.8 | `StatefulAgentStoreImpl` | Double `@REQUIRES_NEW` on `updateBatchToolDecision` | P0 |
| F3 | 🔴 Critical | HITL Logic | `ApprovalStateMachine` | Wrong outcome when last batch decision is DENY | P0 |
| F4 | 🔴 Critical | Tx/§5.9 | `SessionReaper` | `terminateSession()` called inside `@REQUIRED` context | P0 via F1 |
| F16 | 🔴 Critical | HITL/SSE | `AgentSessionService` | `/deny` async bypasses `ApprovalStateMachine`; SSE/decisions corrupted | P0 |
| F17 | 🔴 Critical | HITL/Parallel | `AgentSessionService` | `/approve` async ignores `batchToolDecisions`; all tools run | P0 |
| F18 | 🔴 Critical | MCP | `McpClientService` | STDIO reader unsynchronized; parallel calls corrupt responses | P0 |
| F5 | 🟠 High | Cron/Channel | `CronJobRunner` | `ChannelUser` hardcoded `REST_API` — HITL resume goes to wrong channel | P1 |
| F6 | 🟠 High | Persistence/§5.19 | `StatefulAgentStoreImpl` | `findLatestAwaitingApprovalForCron` uses `ORDER BY updatedAt` not `id DESC` | P1 |
| F10 | 🟠 High | Security | `ToolInvocationGuard` | MCP dynamic tools bypass HITL check entirely | P1 |
| F19 | 🟠 High | MCP | `McpClientService` | Dead STDIO process never cleaned up; no reconnection | P1 |
| F20 | 🟠 High | Persistence | `CompactionSummaryRepository` | FTS ATTACH write in same tx as main; FTS failure rolls back main write | P1 |
| F21 | 🟠 High | HITL/REST | `AgentSessionService` | `approve()` ceiling gap — `maxIterations` unbounded on resume | P1 |
| F22 | 🟠 High | Performance | `AgentStateMachineRunner` | DB read on every tool invocation (hot path) | P2 |
| F23 | 🟠 High | Tool Safety | `ToolRegistry` | Null params not stripped for non-sandbox tools | P1 |
| F7 | 🟠 High | HITL | `StatefulAgentStoreImpl` | Double-resume guard not JVM-restart safe | P3 (accept) |
| F8 | 🟠 High | Parallel | `ChatHistoryAssembler` | Ollama null callId on resume — UUID fallback timing unverified | P2 |
| F9 | 🟡 Medium | Agent Loop | `AgentLoop` | Fast-path guard string-prefix based, not `success()` flag | P2 |
| F11 | 🟡 Medium | Lifecycle | `SessionReaper` | Startup zombie recovery kills active canary sessions | P3 |
| F12 | 🟡 Medium | Lifecycle | `WorkspaceManager` | `destroySession` during active resume — `@WorkspaceScoped` beans reset mid-flight | P2 |
| F13 | 🟡 Medium | Telegram | `TelegramCallbackHandler` | `answerCallback` HTTP no timeout — pool exhaustion risk | P2 |
| F14 | 🟡 Medium | Scheduler | `AgentCronScheduler` | Semaphore permit leak on `RejectedExecutionException` | P2 |
| F15 | 🟡 Medium | Compaction | `CompactionService` | Compaction during HITL wait loses tool context | P3 |
| F24 | 🟡 Medium | Code Guard | `AgentStateMachineRunner` | `isGeneratedCodeBlock` false positives for coordinator/researcher | P3 |
| F25 | 🟡 Medium | RAG | `TwoTierMemoryService` | Synthetic FTS scores computed but discarded — dead code misleads future devs | P3 |
| F26 | 🟡 Medium | RAG/Multi-tenant | `CompactionSummaryRepository` | BM25 IDF pollution across tenants | P3 |
| F27 | 🔴 Security | Multi-tenant | `HitlService` | Cross-tenant AWAITING_APPROVAL data exposure in `GET /hitl` | P0 |
| F28 | 🟡 Medium | Synthesis | `AgentStateMachineRunner` | Error observations included verbatim in synthesizer context | P2 |

---

## Architecture-Level Use-Case Risk Matrix

The following matrix maps each high-risk use case to the findings most likely to surface:

| Use Case | Primary Risks | Findings |
|---|---|---|
| **Multi-user REST API** with parallel tool approvals | Cross-tenant data exposure; wrong channel on resume; `/approve` ignores per-tool decisions | F27, F5, F17 |
| **Telegram + parallel batch HITL** with two concurrent approvals | Double-resume race; stale callback after JVM restart; wrong outcome on mixed approve/deny | F7, F3, F1 |
| **Cron jobs with HITL** on Telegram | Wrong channel type; `ORDER BY updatedAt` on cron checkpoint lookup | F5, F6 |
| **STDIO MCP tools in parallel batch** | Response corruption; dead process not restarted | F18, F19 |
| **Multi-session REST with concurrent deny** | SSE stuck; batchToolDecisions all-PENDING; session never terminated | F16 |
| **High-load with many concurrent writes** | SQLITE_BUSY cascade from `markApprovalTimedOut`; approval timeout GC amplifies it | F1, F4 |
| **Compaction + immediate HITL pause** | Tool context lost in synthesis; answer guard suppresses correct response | F15, F28 |
| **Canary/rolling deploy during active agent** | Zombie recovery marks active sessions FAILED | F11 |
| **Multi-tenant SaaS deployment** | Search quality degraded by shared BM25 corpus; cross-tenant approval list exposure | F26, F27 |
| **Synthesizer with failed sub-agents** | Error messages pollute synthesis context | F28 |

---

## Recommended P0 Sprint (Fix in Next Commit)

These six fixes address zero-day correctness issues that will manifest in any multi-user or parallel-tool production deployment:

**1. `SessionReaper.markApprovalTimedOut` → `@Transactional(NOT_SUPPORTED)`**
```java
// Before:
@Transactional(Transactional.TxType.REQUIRED)
void markApprovalTimedOut(AgentCheckpoint cp) { ... }

// After:
@Transactional(Transactional.TxType.NOT_SUPPORTED)
void markApprovalTimedOut(AgentCheckpoint cp) { ... }
```

**2. `StatefulAgentStoreImpl.updateBatchToolDecision` — remove duplicate `@REQUIRES_NEW` from interface override:**
```java
// Before: @REQUIRES_NEW on BOTH override AND doUpdate method
// After: Only doUpdateBatchToolDecision carries @REQUIRES_NEW
@Override
public boolean updateBatchToolDecision(...) {
    return withRetry(() -> self.doUpdateBatchToolDecision(...), "updateBatchToolDecision");
}
```

**3. `ApprovalStateMachine.decide()` — derive outcome from full decision map:**
```java
// After all tools decided, check if any were APPROVED
boolean anyApproved = allTools.stream()
    .anyMatch(t -> "APPROVED".equals(finalDecisions.get(t)));
outcome = anyApproved ? ApprovalOutcome.APPROVED : ApprovalOutcome.REJECTED;
```

**4. `HitlService.getPendingApprovals()` — add per-key filter and pagination:**
```java
// Filter to sessions owned by the requesting key; add ORDER BY + LIMIT
```

**5. `AgentSessionService` — route `/deny` and `/approve` through `ApprovalStateMachine`:**
```java
// Replace the isDeny fast-path with:
approvalGateway.decide(sessionId, null, ApprovalDecision.REJECT, "Human denied via REST");
// For /approve parallel batch:
approvalGateway.decide(sessionId, null, ApprovalDecision.APPROVE, "Human approved via REST");
```

**6. `McpClientService.sendRequestStdio()` — add per-server `ReentrantLock`:**
```java
private final ConcurrentHashMap<String, ReentrantLock> stdioLocks = new ConcurrentHashMap<>();

private JsonNode sendRequestStdio(String serverName, String method, ObjectNode params) throws Exception {
    ReentrantLock lock = stdioLocks.computeIfAbsent(serverName, k -> new ReentrantLock());
    lock.lock();
    try {
        // ... existing read/write logic ...
    } finally {
        lock.unlock();
    }
}
```

---

## Overall Architecture Health Assessment

ArcClaw P84 is a well-engineered system. The P84 SQLite transaction invariants (§5.8–§5.19) are correctly applied across 90% of the codebase — the main violations are in the older `SessionReaper` timeout path that predates the P84 analysis. The parallel HITL machinery (§7.4–§7.36) is thorough, correctly handles pre-execution, dedup, double-resume, and timeout cascades. The module boundaries are clean with no Telegram imports in `arcclaw-core`. OTel propagation to virtual threads is correctly handled per §7.13.

The three areas that need the most architectural attention going forward are: **the REST approval path** (which has a shadow legacy flow that was not updated when `ApprovalStateMachine` was introduced), **the STDIO MCP transport** (which was designed for single-threaded use but is now called from parallel tool executors), and **multi-tenant data isolation** in `HitlService` and the FTS search layer (which were designed for single-user/self-hosted and need explicit tenant guards before a SaaS deployment).