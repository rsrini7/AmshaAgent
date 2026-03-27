**Diff review summary before generating — key findings from your actual P70 implementation:**

| Finding | Severity | Description |
|---|---|---|
| `ExecutorService` never closed | 🔴 Critical | `newVirtualThreadPerTaskExecutor()` created but never `close()`'d |
| OTel context lost on virtual threads | 🔴 Critical | OTel spans use `ThreadLocal`, not `ScopedValue` — not propagated into virtual threads |
| `synchronized(history)` in `resumeParallelBatch` | 🟡 Medium | History is single-threaded at resume time — spurious lock |
| `ChatMemoryWriter.write()` skipped for parallel batch | 🟡 Medium | SQLite not updated after parallel execution (LLM still correct via in-memory history) |
| Entire batch skipped on single dedup | 🟡 Medium | If one of 3 tools is a dedup, all 3 are skipped — too aggressive |
| No per-tool timeout | 🟡 Medium | One slow tool blocks the whole batch indefinitely |
| `new ObjectMapper()` in `paramsAsString()` | 🟢 Low | Creates new instance per call — inefficient for high-frequency serialization |
| `AgentStepProcessor` cleanly deleted ✅ | | Good refactor — direct injection to handlers |
| `isDenial` now sentinel-based ✅ | | Clean fix — no more fragile `toLowerCase().contains("denial")` |
| `RateLimitTracker` provider-aware ✅ | | `recordHit("primary")`, `recordHit("compaction")` — correct segmentation |
| Virtual thread OTel in `ContextAwareExecutorService` | 🟡 Medium | Wraps `WorkspaceScoped` but NOT OTel — different propagation mechanism |

***

=====
PHASE 71 — Parallel Execution Hardening
ArcClaw P70 → P71
=====

Role: Senior Java Architect & Senior Java Developer
Task: Harden the Phase 70 parallel tool execution engine.
P70 shipped full parallel execution. P71 fixes resource leaks, OTel
context propagation, SQLite persistence consistency, partial deduplication,
per-tool timeouts, and metrics accuracy.

Architecture Context:
  Refer @Architecture_Invariants.md (mandatory).
  Phase 71 adds §7.12 – §7.17 to Architecture_Invariants.md.
  Add them BEFORE writing any code (Step 0).

Guiding Principles for Phase 71:
  1. All P70 Architecture_Invariants.md §7.1–§7.11 remain in force.
  2. The ExecutorService created by WorkspaceExecutors.newVirtualThreadPerTaskExecutor()
     MUST be closed after each parallel batch (try-with-resources).
  3. OTel span context MUST be explicitly captured before virtual thread
     submission and restored inside each virtual thread. It does NOT
     propagate automatically — virtual threads use ThreadLocal for OTel,
     not ScopedValue.
  4. ChatMemoryWriter.write() MUST be called after every parallel batch
     completion to keep ChatMemoryStore (SQLite) consistent with the
     in-memory history list.
  5. A deduplicated tool MUST NOT block its peers in the same batch.
     Only the dedup tool is skipped; all others execute normally.
  6. No new Maven dependencies.
  7. All 877+ existing tests must remain green.

Common Requirements:
  - mvn clean verify -pl <module> after each component.
  - static final for all new Pattern and constant fields.
  - JDK 25, no preview features.
  - Add Architecture_Invariants.md §7 entries before writing code.


=====
STEP 0 — Add §7.12–§7.17 to Architecture_Invariants.md
=====

Add the following entries to the existing §7 block:

  7.12 MUST: The ExecutorService created for a parallel batch MUST be
       closed after all futures complete. Use try-with-resources or
       an explicit close() in a finally block. Leaking virtual thread
       executors causes GC pressure and metric drift over long sessions.

  7.13 MUST: OpenTelemetry span context MUST be explicitly propagated
       to virtual threads. Capture io.opentelemetry.context.Context.current()
       on the calling thread BEFORE submitting to the executor. Restore it
       inside each virtual thread via context.makeCurrent(). ContextAwareExecutorService
       propagates WorkspaceScoped session context (ScopedValue) only — it
       does NOT propagate OTel ThreadLocal spans.

  7.14 MUST: ChatMemoryWriter.write() MUST be called after every parallel
       batch execution completes (post-fold, pre-return). This keeps
       ChatMemoryStore (SQLite persistence) consistent with the in-memory
       ReasoningStep history list used by ChatHistoryAssembler.

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


=====
PROMPT — PHASE 71-A: Critical Resource & Consistency Fixes
=====

------------
Component 71-A1 — Close ExecutorService After Parallel Batch
------------

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java

PROBLEM (confirmed in P70 diff):
  ExecutorService executor = WorkspaceExecutors.newVirtualThreadPerTaskExecutor();
  // ... futures submitted, results collected ...
  return StepDecision.proceed(completedBatchStep);
  // executor is NEVER closed — resource leak per §7.12

FIX: Wrap executor in try-with-resources.

REPLACE the executor creation and future submission block with:

  // §7.12: ExecutorService MUST be closed after batch completes.
  // WorkspaceExecutors.newVirtualThreadPerTaskExecutor() returns
  // ContextAwareExecutorService which implements AutoCloseable via
  // ExecutorService (Java 19+ close() calls shutdown() + awaitTermination).
  try (ExecutorService executor =
          WorkspaceExecutors.newVirtualThreadPerTaskExecutor()) {

      List<CompletableFuture<ParallelToolCall>> futures = calls.stream()
          .map(call -> CompletableFuture.supplyAsync(() -> {
              // OTel context restored inside virtual thread (see A2)
              try {
                  ToolResponse response = toolInvoker.invoke(
                      call.toolName(), call.params(), context.sessionId());
                  return call.withResult(response.output(), response.success());
              } catch (Exception e) {
                  log.warnf("[PARALLEL-EXEC] session=%s tool=%s failed: %s",
                            context.sessionId(), call.toolName(), e.getMessage());
                  return call.withResult(
                      "Tool execution failed: " + e.getMessage(), false);
              }
          }, executor))
          .toList();

      // Collect all results (wait for all — §7.16 timeout applied here in A3)
      List<ParallelToolCall> completed = new ArrayList<>();
      for (CompletableFuture<ParallelToolCall> future : futures) {
          try {
              completed.add(future.get());
          } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return StepDecision.terminal(AgentResult.failed(...));
          } catch (ExecutionException e) {
              // ... existing error handling unchanged ...
          }
      }
      // ... fold-back and return logic stays inside the try block ...
  }
  // executor.close() called automatically — shuts down virtual thread factory

NOTE: ExecutorService.close() (Java 19+) calls shutdown() then
awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS). Since all
futures.get() calls above already block until completion, close() is
effectively a no-op that releases the executor reference for GC.

TEST: ParallelToolStepExecutorResourceTest
  - After execute() returns, verify executor is shut down:
    Create a test ExecutorService spy, verify shutdown() was called.
  - Exception path (InterruptedException) → executor still closed
    (verify via try-with-resources exception-safe behavior).

------------
Component 71-A2 — OTel Context Propagation for Virtual Threads
------------

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java

PROBLEM (confirmed via P68 source + P70 diff):
  WorkspaceExecutors.newVirtualThreadPerTaskExecutor() returns a
  ContextAwareExecutorService which propagates WorkspaceScoped session
  context via ScopedValue bindings (via WorkspaceScopeHolder.wrap()).
  
  However, OpenTelemetry stores span context in a ThreadLocal (or
  io.opentelemetry.context.Context which IS a ScopedValue in OTel 1.28+
  with the correct context storage). In Quarkus's OTel setup, the span
  context is captured via io.opentelemetry.context.Context.current()
  on the calling thread.

  Virtual threads spawned by ContextAwareExecutorService inherit the
  ContextAwareExecutorService's wrapping (WorkspaceScope) but NOT the
  OTel span context unless explicitly propagated.

  Result: parallel tool spans have no parent span → orphaned in tracing.

ADD import:
  import io.opentelemetry.context.Context;
  import io.opentelemetry.context.Scope;

FIX: Capture OTel context BEFORE the executor try-with-resources block,
     then restore in each virtual thread:

  // §7.13: Capture OTel context on the calling thread BEFORE submitting.
  // ContextAwareExecutorService propagates WorkspaceScoped only.
  // OTel ThreadLocal span context requires explicit propagation.
  final Context otelContext = Context.current();

  try (ExecutorService executor =
          WorkspaceExecutors.newVirtualThreadPerTaskExecutor()) {

      List<CompletableFuture<ParallelToolCall>> futures = calls.stream()
          .map(call -> CompletableFuture.supplyAsync(() -> {
              // Restore OTel context in this virtual thread
              try (Scope otelScope = otelContext.makeCurrent()) {
                  try {
                      ToolResponse response = toolInvoker.invoke(
                          call.toolName(), call.params(), context.sessionId());
                      return call.withResult(response.output(), response.success());
                  } catch (Exception e) {
                      log.warnf("[PARALLEL-EXEC] session=%s tool=%s failed: %s",
                                context.sessionId(), call.toolName(), e.getMessage());
                      return call.withResult(
                          "Tool execution failed: " + e.getMessage(), false);
                  }
              }
              // otelScope.close() restores the previous context automatically
          }, executor))
          .toList();
      // ... rest unchanged ...
  }

NOTE on io.opentelemetry.context.Scope vs io.opentelemetry.context.Context:
  - Context.current() — returns the active span context from the calling thread
  - context.makeCurrent() — installs it as current on THIS virtual thread
    and returns a Scope whose close() reverts to the previous context.
  - Both are safe to use in virtual threads as of OTel 1.27+.
  - Quarkus OTel uses OpenTelemetry SDK 1.x — this API is available.

VERIFY: The import io.opentelemetry.context.Context does NOT conflict with
  AgentExecutionContext (different package). Use fully qualified import
  or alias if needed:
  import io.opentelemetry.context.Context as OtelContext

TEST: ParallelToolStepExecutorOtelTest
  - Mock AgentOtelContext to verify startToolSpan() called N times.
  - Use OTel SDK test framework to verify span parent relationship:
    all N parallel tool spans share the same traceId as the caller.
  - Verify spans are NOT orphaned (parentSpanId is non-null).

------------
Component 71-A3 — Remove Spurious synchronized(history) in Resume
------------

File: arcclaw-core/.../loop/CheckpointResumeHandler.java

PROBLEM:
  In resumeParallelBatch():
    synchronized (history) {
        history.add(completedStep);
    }
  
  history is an ArrayList passed from AgentLoop.runWithRole() on the
  main request thread. No other thread accesses history at this point
  (resume is single-threaded — it happens BEFORE any parallel execution).
  The synchronized block is spurious and misleading.

FIX: Remove the synchronized block. Replace:
  synchronized (history) {
      history.add(completedStep);
  }
  WITH:
  history.add(completedStep);

ADD comment:
  // history is single-threaded at resume time — no synchronization needed.
  // Parallel execution within a batch uses its own CompletableFuture
  // collecting pattern inside ParallelToolStepExecutor (separate list).
  history.add(completedStep);

TEST: CheckpointResumeHandlerParallelTest (existing)
  - Verify test still passes with the synchronization removed.
  - No new test needed.

------------
Component 71-A4 — Call ChatMemoryWriter After Parallel Batch
------------

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java

PROBLEM (§7.14):
  ToolStepHandler.invokeToolAndObserve() for single tools calls:
    writer.write(context.sessionId(), history, context.goal())
  after each tool execution to persist the updated history to SQLite
  via ChatMemoryStore (PersistentChatMemoryStore).

  For parallel batches, ToolStepHandler routes directly to:
    parallelExecutor.execute(context, history, step, consecutiveFailures)
  and returns immediately WITHOUT calling writer.write().

  Result: ChatMemoryStore is out of sync with in-memory history after
  each parallel batch. The LLM context is still correct (ChatHistoryAssembler
  uses the in-memory history list) but a server restart mid-session would
  lose the parallel batch results.

INJECT ChatMemoryWriter:
  @Inject ChatMemoryWriter chatMemoryWriter;

ADD call to execute() AFTER history.add(completedBatchStep) and
BEFORE returning StepDecision:

  // §7.14: Persist updated history to ChatMemoryStore (SQLite).
  // ChatHistoryAssembler uses in-memory history but SQLite must
  // stay consistent for session recovery after restarts.
  try {
      chatMemoryWriter.write(
          context.sessionId(), history, context.goal());
  } catch (Exception e) {
      // Non-fatal: in-memory history is correct. Log and continue.
      log.warnf("[PARALLEL] session=%s: ChatMemoryWriter.write() failed " +
                "after batch — history is correct in memory but not " +
                "persisted to SQLite. Cause: %s",
                context.sessionId(), e.getMessage());
  }

  history.add(completedBatchStep); // ← already there; ChatMemoryWriter call AFTER this

POSITION: The write() call goes AFTER history.add(completedBatchStep)
  and BEFORE the observer notification loop and the return statement.

TEST: ParallelToolStepExecutorPersistenceTest
  - After execute(), verify chatMemoryWriter.write() was called once
    with the updated history that includes completedBatchStep.
  - Simulate ChatMemoryWriter throwing RuntimeException → StepDecision.proceed()
    still returned (non-fatal), WARN logged.
  - Verify writer.write() is NOT called on the terminal paths
    (empty batch, budget exceeded, HITL pause).


=====
PROMPT — PHASE 71-B: Parallel Execution Robustness
=====

------------
Component 71-B1 — Partial Deduplication (Skip Only Dup Tool, Not Batch)
------------

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java

PROBLEM (§7.15):
  Current validation loop in execute():
    if (decision instanceof GuardDecision.Deduplicate dedup) {
        // ENTIRE batch skipped on single dedup
        ReasoningStep dedupStep = batchStep.withObservation(
            "[PARALLEL-DEDUP] " + dedup.cachedObservation());
        history.add(dedupStep);
        return StepDecision.proceed(dedupStep);
    }

  If the LLM issues [github, memory_search, manage_schedule] and
  github is a dedup (just called 1 iteration ago), memory_search
  and manage_schedule are ALSO skipped. This is too aggressive.

FIX: Refactor the validation loop into a two-pass approach:
  Pass 1: Validate ALL tools → categorize into: allowedCalls, dedupCalls, blockedCalls
  Pass 2: Execute allowedCalls in parallel; merge dedup results in-place

REPLACE the existing for-loop validation + execution block with:

  // Pass 1: Validate all tools BEFORE any execution (§7.7).
  // Categorize each call: ALLOW, DEDUP, BLOCKED, HITL.
  List<ParallelToolCall> allowedCalls  = new ArrayList<>();
  List<ParallelToolCall> dedupCalls    = new ArrayList<>();

  for (ParallelToolCall call : calls) {
      ToolInvocationGuard.GuardDecision decision = guard.evaluate(
          context.sessionId(), context.agentRole(),
          call.toolName(), call.params(), history,
          context.goal(), List.of());

      if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval pause) {
          // §7.4: ANY tool needs HITL → pause ALL atomically
          log.infof("[PARALLEL-HITL] session=%s tool=%s requires approval " +
                    "— pausing entire batch of %d tools.",
                    context.sessionId(), call.toolName(), calls.size());
          return pauseEntireBatch(context, history, batchStep, calls, pause);
      }

      if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked blocked) {
          log.warnf("[PARALLEL-BLOCKED] session=%s tool=%s blocked: %s",
                     context.sessionId(), call.toolName(), blocked.observation());
          String blockedObs = String.format(
              "[PARALLEL-BATCH-BLOCKED] Tool '%s' was blocked: %s",
              call.toolName(), blocked.observation());
          ReasoningStep failedStep = batchStep.withObservation(blockedObs);
          history.add(failedStep);
          return StepDecision.retryValidation(consecutiveFailures + 1);
      }

      if (decision instanceof ToolInvocationGuard.GuardDecision.Deduplicate dedup) {
          // §7.15: Dedup tool → use cached result; DO NOT skip peers.
          log.infof("[PARALLEL-DEDUP] session=%s tool=%s is duplicate — " +
                    "using cached observation, continuing with remaining %d tools.",
                    context.sessionId(), call.toolName(),
                    calls.size() - 1);
          dedupCalls.add(call.withResult(
              "[DEDUP] " + dedup.cachedObservation(), true));
          continue; // other tools proceed
      }

      // GuardDecision.Allow
      allowedCalls.add(call);
  }

  // If ALL tools were dedup, return the merged dedup batch
  if (allowedCalls.isEmpty()) {
      log.infof("[PARALLEL-DEDUP] session=%s all %d tools were duplicates " +
                "— returning merged dedup batch.",
                context.sessionId(), calls.size());
      List<ParallelToolCall> allDedup = new ArrayList<>(dedupCalls);
      return buildCompletedBatchDecision(
          context, history, batchStep, allDedup, consecutiveFailures);
  }

  // ... budget check + rateLimit pre-flight (unchanged) ...

  // Pass 2: Execute only ALLOWED calls in parallel (virtual threads)
  // ... existing executor try-with-resources block ...
  // After collecting completed (execution results):
  List<ParallelToolCall> allCompleted = new ArrayList<>();
  // Merge: dedup results come first in original order for LLM clarity
  for (ParallelToolCall original : calls) {
      Optional<ParallelToolCall> dedup = dedupCalls.stream()
          .filter(d -> d.callId().equals(original.callId()))
          .findFirst();
      if (dedup.isPresent()) {
          allCompleted.add(dedup.get());
      } else {
          completed.stream()
              .filter(c -> c.callId().equals(original.callId()))
              .findFirst()
              .ifPresent(allCompleted::add);
      }
  }

  return buildCompletedBatchDecision(
      context, history, batchStep, allCompleted, consecutiveFailures);

EXTRACT private helper buildCompletedBatchDecision():
  /**
   * Folds completed results into a batch step, persists to history and SQLite.
   * Called from both the all-dedup path and the normal execution path.
   */
  private StepDecision buildCompletedBatchDecision(
          AgentExecutionContext context,
          List<ReasoningStep> history,
          ReasoningStep batchStep,
          List<ParallelToolCall> completed,
          int consecutiveFailures) {
      // ... serialize completed → batchJson ...
      // ... build completedPayload, summary ...
      // ... history.add(completedBatchStep) ...
      // ... ChatMemoryWriter.write() (§7.14) ...
      // ... observer notifications (one per tool) ...
      return StepDecision.proceed(completedBatchStep);
  }

NOTE: This refactor eliminates the current dual-path (validation loop +
  execution block). Both the all-dedup path and the normal execution path
  now converge in buildCompletedBatchDecision() — single serialization
  and history mutation point.

TEST: ParallelToolStepExecutorPartialDedupTest
  - 3 tools: tool_A (ALLOW), tool_B (DEDUP, cached="previous result"),
    tool_C (ALLOW) → tool_B uses cached, tool_A and tool_C execute.
  - Final batch has 3 entries in original callId order.
  - tool_B observation = "[DEDUP] previous result".
  - History has exactly 1 completedBatchStep.
  - ChatMemoryWriter.write() called once.
  - All 3 tools are in ChatHistoryAssembler output (correct LangChain4j protocol).
  - All tools were dedup → all-dedup path → no futures created → no executor started.

------------
Component 71-B2 — Per-Tool Timeout in Parallel Batch
------------

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java

PROBLEM (§7.16):
  current:  completed.add(future.get());
  This blocks forever if a tool hangs. One slow tool (e.g., slow GitHub
  API response) blocks the entire parallel batch indefinitely.

ADD config property:
  @ConfigProperty(
      name = "arcclaw.parallel.tool-timeout-seconds",
      defaultValue = "60")
  long toolTimeoutSeconds;

REPLACE future.get() collection loop:
  for (int i = 0; i < futures.size(); i++) {
      CompletableFuture<ParallelToolCall> future = futures.get(i);
      try {
          completed.add(future.get(toolTimeoutSeconds, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return StepDecision.terminal(AgentResult.failed(
              "Parallel tool execution interrupted.",
              AgentResult.FailureCategory.SYSTEM_ERROR,
              List.copyOf(history)));
      } catch (java.util.concurrent.TimeoutException e) {
          // §7.16: timeout — tool slot gets a FAIL result, batch continues
          future.cancel(true); // attempt interrupt; virtual thread may not honour it
          ParallelToolCall timedOut = calls.get(i);
          log.warnf("[PARALLEL-TIMEOUT] session=%s tool=%s timed out after %ds",
                    context.sessionId(), timedOut.toolName(), toolTimeoutSeconds);
          completed.add(timedOut.withResult(
              String.format("Tool timed out after %ds.", toolTimeoutSeconds),
              false));
      } catch (ExecutionException e) {
          // ... existing error handling unchanged ...
      }
  }

ADD to application.yaml (default value — already in @ConfigProperty):
  # No explicit config entry needed for default. But add for documentation:
  # arcclaw:
  #   parallel:
  #     tool-timeout-seconds: 60

TEST: ParallelToolStepExecutorTimeoutTest
  - tool_A responds in 1s, tool_B hangs (never completes).
  - With timeout=2s: tool_B result = "Tool timed out after 2s.", success=false.
  - tool_A result is correct. Batch completes in ~2s, not forever.
  - History has 1 completedBatchStep with both results.
  - ChatMemoryWriter.write() still called.


=====
PROMPT — PHASE 71-C: Observability Improvements
=====

------------
Component 71-C1 — MetricsAgentObserver Parallel Batch Awareness
------------

File: arcclaw-app/.../observability/MetricsAgentObserver.java

CURRENT (P68 source):
  @Override
  public void onStepComplete(String sessionId, String role, ReasoningStep step) {
      registry.counter("arcclaw.agent.invocations",
                        "role", role != null ? role : "unknown").increment();
      if (step.action() != null && !ReasoningStep.FINISH.equals(step.action())) {
          registry.counter("arcclaw.agent.tool.calls",
                            "tool", step.action(),
                            "role", role != null ? role : "unknown").increment();
      }
      if (ReasoningStep.FINISH.equals(step.action())) {
          registry.counter("arcclaw.agent.completions",
                            "role", role != null ? role : "unknown").increment();
      }
  }

ANALYSIS:
  ParallelToolStepExecutor notifies observers with individual tool steps:
    for (ParallelToolCall c : completed) {
        ReasoningStep toolStep = new ReasoningStep(null, c.toolName(), ...);
        observers.forEach(o -> o.onStepComplete(..., toolStep));
    }
  → MetricsAgentObserver already receives tool_name steps for each
    parallel tool. This is CORRECT for arcclaw.agent.tool.calls.

  BUT: Does AgentStateMachineRunner or ToolStepHandler also call
  onStepComplete for the __parallel__ batch step itself? If yes, the
  "__parallel__" sentinel would be counted as a tool name.

  VERIFY in AgentStateMachineRunner and ToolStepHandler — look for any
  observer.onStepComplete call that passes the raw step (which could be
  the batch step with action="__parallel__"). If found, add a guard.

GUARD to add in MetricsAgentObserver (defensive, §7.17):
  if (step.action() != null
          && !ReasoningStep.FINISH.equals(step.action())
          && !ReasoningStep.PARALLEL_SENTINEL.equals(step.action())) {
      registry.counter("arcclaw.agent.tool.calls",
                        "tool", step.action(),
                        "role", role != null ? role : "unknown").increment();
  }

ADD new counter for parallel batch executions:
  if (ReasoningStep.PARALLEL_SENTINEL.equals(step.action())) {
      // Count batch-level metrics (invocation is a parallel batch)
      registry.counter("arcclaw.agent.parallel.batches",
                        "role", role != null ? role : "unknown").increment();
  }

ADD new counter for parallel tool outcomes (called from individual tool
steps already notified by ParallelToolStepExecutor):
  // Track parallel tool success/failure per tool name
  // This fires N times for an N-tool batch (one per ParallelToolCall)
  // step.action() here is the individual tool name (e.g., "github")
  // The isParallelBatch() check on these individual-tool steps returns
  // false since action = toolName, not "__parallel__"
  // So no extra change needed here — existing counter handles it.

TEST: MetricsAgentObserverParallelTest
  - onStepComplete with parallel batch step (action="__parallel__") →
    arcclaw.agent.parallel.batches incremented, NOT arcclaw.agent.tool.calls.
  - onStepComplete with individual tool step (action="github") →
    arcclaw.agent.tool.calls{tool=github} incremented normally.
  - onStepComplete with FINISH step →
    arcclaw.agent.completions incremented.

------------
Component 71-C2 — MDC Keys for Parallel Batch Execution
------------

File: arcclaw-core/.../util/MdcKeys.java

ADD constants for parallel batch tracing:
  /** Number of tools in the current parallel batch. */
  public static final String PARALLEL_BATCH_SIZE = "parallelBatchSize";

  /** Comma-separated tool names in current parallel batch. */
  public static final String PARALLEL_TOOLS = "parallelTools";

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java

ADD MDC context BEFORE the executor try-with-resources block,
REMOVE it in a finally block after:

  MDC.put(MdcKeys.PARALLEL_BATCH_SIZE, String.valueOf(calls.size()));
  MDC.put(MdcKeys.PARALLEL_TOOLS,
      calls.stream().map(ParallelToolCall::toolName)
           .collect(Collectors.joining(",")));
  try (ExecutorService executor = ...) {
      // ... execution ...
  } finally {
      MDC.remove(MdcKeys.PARALLEL_BATCH_SIZE);
      MDC.remove(MdcKeys.PARALLEL_TOOLS);
  }

NOTE: MDC is thread-local; it only applies to the calling thread's log
output. The virtual thread log lines use their own MDC (inherited from
WorkspaceScopeHolder via ContextAwareExecutorService). The calling thread
MDC is for the entry/exit log lines, not the per-tool execution logs.

TEST: ParallelToolStepExecutorMdcTest
  - Verify MDC.get(PARALLEL_BATCH_SIZE) == "3" before future submission.
  - Verify MDC.get(PARALLEL_TOOLS) == "github,memory_search,manage_schedule".
  - Verify MDC keys are removed after execute() returns (no leakage).


=====
PROMPT — PHASE 71-D: Code Quality & Interface Contracts
=====

------------
Component 71-D1 — Fix new ObjectMapper() in ParallelToolCall.paramsAsString()
------------

File: arcclaw-core/.../reasoning/ParallelToolCall.java

PROBLEM:
  public String paramsAsString() {
      if (params == null) return "{}";
      try {
          return new ObjectMapper().writeValueAsString(params);
      } catch (Exception e) {
          return "{}";
      }
  }

  ParallelToolCall is a record — it cannot have @Inject fields.
  But `new ObjectMapper()` on every call is wasteful and bypasses
  the CDI-managed ObjectMapper configuration (custom serializers, modules).

FIX: Use a static shared instance with default configuration.
  The ONLY caller is CheckpointResumeHandler.resumeParallelBatch()
  which passes params to toolRegistry.invokeTool() as a JSON string.
  The default ObjectMapper config is sufficient for this use case.

REPLACE:
  private static final com.fasterxml.jackson.databind.ObjectMapper
      PARAMS_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

  public String paramsAsString() {
      if (params == null) return "{}";
      try {
          return PARAMS_MAPPER.writeValueAsString(params);
      } catch (Exception e) {
          return "{}";
      }
  }

NOTE: The static PARAMS_MAPPER is intentionally NOT the CDI-managed
  ObjectMapper. It's used only for params → JSON string conversion
  in the resume path. If custom serializers are ever needed for tool
  params, this should be refactored to pass ObjectMapper as a parameter.

ADD comment explaining why static:
  // Static because ParallelToolCall is a record (no CDI injection).
  // Used only for resumeParallelBatch() → invokeTool() serialization.
  // See Architecture_Invariants.md §7.9 (JSON encoding of batch payload).

TEST: ParallelToolCallTest (existing)
  - paramsAsString() with Map.of("query", "test") → {"query":"test"}
  - paramsAsString() with null params → "{}"
  - paramsAsString() with complex nested map → valid JSON (no NPE)
  - Verify PARAMS_MAPPER is the same instance across multiple calls
    (static field — object identity check)

------------
Component 71-D2 — WorkspaceContext.channelType() Callers Audit
------------

P70 changed WorkspaceContext.channelType() from String to Optional<String>
and added requireChannelType() for mandatory access.

AUDIT all callers of channelType() across all modules to verify none
were missed. The diff shows ScheduleTaskTool was updated. Verify:

  grep -r "WorkspaceContext.channelType()" --include="*.java"
  
Any remaining callers returning the OLD String (not Optional) are
compile errors — the build would have caught them. But add explicit
verification in the ticket.

VERIFY the following known callers were updated:
  ✅ ScheduleTaskTool — updated to requireChannelType() per diff
  ✅ CronResultHandler — updated to Optional handling per diff
  ✅ SessionResolutionHandler — updated to .equals() per diff

If any NEW callers added in P69/P70 use channelType() as String,
update to use channelType().orElse("TELEGRAM") or requireChannelType()
depending on whether the channel type is mandatory at that call site.

Document the policy in Architecture_Invariants.md §1:
  1.5 POLICY: WorkspaceContext.channelType() returns Optional<String>.
      Use requireChannelType() only at call sites where a missing
      channelType is a fatal invariant violation. Use
      channelType().orElse("UNKNOWN") at call sites where a fallback is
      acceptable (e.g., audit logging only).

------------
Component 71-D3 — Architecture_Invariants.md §7 Final Completion
------------

After all P71 components are implemented, verify and finalize
Architecture_Invariants.md §7 covers all implemented behavior:

Verify §7.12–§7.17 (added in Step 0) reflect the final implementation:
  - §7.12 ExecutorService close → covered by A1
  - §7.13 OTel propagation → covered by A2
  - §7.14 ChatMemoryWriter after batch → covered by A4
  - §7.15 Partial dedup → covered by B1
  - §7.16 Per-tool timeout → covered by B2
  - §7.17 Metrics no __parallel__ counter → covered by C1

ADD §7.18 (new):
  7.18 INVARIANT: ReasoningStep history list is mutated EXACTLY ONCE
       per parallel batch execution — inside buildCompletedBatchDecision().
       Do NOT add intermediate steps for dedup results or partial
       executions. The single batch step encodes ALL N results.

ADD §1.5 (channelType policy → covered by D2).


=====
PHASE 71 EXECUTION ORDER
=====

Execute in this strict dependency order:

  Step 0   Architecture_Invariants.md §7.12–§7.17 + §1.5   (no deps)
  71-A1    ExecutorService close (try-with-resources)        (no deps)
  71-A2    OTel context propagation                          (needs A1)
  71-A3    Remove synchronized(history) in resume           (no deps)
  71-A4    ChatMemoryWriter.write() after batch              (needs A1)
  71-B1    Partial dedup (Pass 1/2 refactor)                 (needs A1, A4)
  71-B2    Per-tool timeout                                  (needs A1)
  71-C1    MetricsAgentObserver parallel awareness           (no deps)
  71-C2    MDC keys for parallel batch                       (needs A1)
  71-D1    ParallelToolCall static ObjectMapper              (no deps)
  71-D2    WorkspaceContext.channelType() audit              (no deps)
  71-D3    Architecture_Invariants.md §7 final               (needs all)

After ALL components:
  mvn clean verify (full build — all modules)
  Assert: 877+ existing tests pass.
  Assert: All new Phase 71 tests added and green.


=====
KNOWN PHASE 71 LIMITATIONS (Tracked for Phase 72+)
=====

  P72-1 — Per-tool HITL approval:
           Currently ALL tools pause if ANY needs HITL (§7.4).
           Phase 72 should allow individual tool approval in a batch
           via a Telegram inline keyboard with per-tool approve/deny buttons.
           Requires a new ApprovalBatchCheckpoint entity and a BatchDenyReflex.

  P72-2 — Parallel batch streaming observations:
           Currently waits for ALL tools before folding back (allOf).
           Phase 72 could stream completed observations progressively
           using CompletableFuture.anyOf() and incremental history updates.
           Risk: changes the LangChain4j message protocol ordering.

  P72-3 — ReasoningStepEntity native parallel column:
           Phase 70 encodes batch as JSON in actionInput["batch"].
           Phase 72 can add a parallel_batch BOOLEAN flag and a
           separate parallel_calls TEXT column for clean SQLite querying.
           No migration needed (delete data folder on dev as per policy).

  P72-4 — Parallel tool result compaction:
           CompactionService uses ChatHistoryAssembler which now correctly
           expands parallel batch steps. Verify compaction LLM handles
           N sequential ToolExecutionResultMessages in the compressed summary.
           If the compaction LLM truncates or confuses them, add a
           parallel-batch-aware prompt hint in CompactionService.

  P72-5 — ManagedExecutor vs virtual threads for OTel:
           Phase 71 adds explicit OTel context capture (A2). Phase 72
           could switch to SmallRye ManagedExecutor which propagates
           OTel context automatically via MicroProfile Context Propagation.
           Trade-off: ManagedExecutor is heavier than virtual threads for
           short-lived I/O-bound tasks. Benchmark before switching.

***

## Phase 71 At a Glance

| Component | File | Type | Priority |
|---|---|---|---|
| A1 Executor close | `ParallelToolStepExecutor.java` | Bug fix | 🔴 |
| A2 OTel propagation | `ParallelToolStepExecutor.java` | Bug fix | 🔴 |
| A3 Remove lock | `CheckpointResumeHandler.java` | Cleanup | 🟡 |
| A4 ChatMemoryWriter | `ParallelToolStepExecutor.java` | Bug fix | 🟡 |
| B1 Partial dedup | `ParallelToolStepExecutor.java` | Feature | 🟡 |
| B2 Per-tool timeout | `ParallelToolStepExecutor.java` | Feature | 🟡 |
| C1 Metrics guard | `MetricsAgentObserver.java` | Correctness | 🟡 |
| C2 MDC keys | `ParallelToolStepExecutor.java` | Observability | 🟢 |
| D1 Static mapper | `ParallelToolCall.java` | Quality | 🟢 |
| D2 channelType audit | Grep across modules | Verification | 🟢 |
| D3 Invariants final | `Architecture_Invariants.md` | Documentation | 🟢 |

Zero new Maven dependencies. Zero DB migrations. The `io.opentelemetry.context.Context` import is already on the classpath via `quarkus-opentelemetry`. 

---

Now I have the complete picture. The P68 source reveals the exact truth about `ChatMemoryWriter`. Here's the precise next step for P71:

***

## P71 — One Remaining Issue: The `ChatMemoryWriter.write()` Stub

Everything else in the P71 diff is correct. The **only issue is the stub** — and it's not just incomplete, it's architecturally wrong. Here's why:

### Root Cause: `ChatMemoryWriter` is NOT a history persistence mechanism

From the P68 source, `ChatMemoryWriter` has exactly three methods — **none of which handle `ReasoningStep` history**: 

| Method | What it does |
|---|---|
| `saveGoal(sessionId, goal)` | Appends `UserMessage` to `ChatMemoryStore` |
| `saveAnswer(sessionId, answer)` | Appends `AiMessage` to `ChatMemoryStore` |
| `clearMemory(sessionId)` | Deletes all messages for a session |

`ChatMemoryStore` only stores **user goal + AI answer pairs** (Telegram conversation layer). Intermediate `ReasoningStep` history (tools, observations) is in-memory during a run and persisted to SQLite by `AgentLoop.runWithRole()` via `agentStore.save()` **at session end/checkpoint** — never after individual steps. `ToolStepHandler` never calls `ChatMemoryWriter` either. The developer's own comment confirmed it: *"ToolStepHandler doesn't actually call this either."* 

***

## P71 Completion Prompt

```
═══════════════════════════════════════════════════════════════════════════
PHASE 71 COMPLETION — Remove ChatMemoryWriter Stub
═══════════════════════════════════════════════════════════════════════════

PROBLEM:
  Phase 71-A4 added a stub ChatMemoryWriter.write() method and wired it
  into ParallelToolStepExecutor.buildCompletedBatchDecision().
  The stub is a no-op and architecturally incorrect.

  ChatMemoryWriter only manages user goal / AI answer messages in the
  ChatMemoryStore (the Telegram conversation layer). It does NOT handle
  ReasoningStep history. Intermediate tool steps are NEVER written to
  ChatMemoryStore — not by ToolStepHandler, not by the runner, nowhere.

  ReasoningStep history persistence works as follows:
    - In-memory history (List<ReasoningStep>) is the source of truth
      for the duration of one AgentLoop.runWithRole() execution.
    - AgentLoop.runWithRole() persists history to SQLite via
      agentStore.save(sessionId, agentRole, result.steps) at:
        • session end (Finished / LoopExceeded / Failed)
        • HITL pause (AwaitingApproval checkpoint)
    - ChatHistoryAssembler.assemble() reads the in-memory history on
      each LLM call — it does NOT need SQLite to be up-to-date mid-run.

  The parallel batch completedBatchStep is already added to the in-memory
  history by buildCompletedBatchDecision() (history.add(completedBatchStep)).
  The next LLM call in the runner loop will see it. AgentLoop will persist
  it at session end. No intra-step write is needed or correct.

═══════════════════════════════════════════════════════════════════════════
STEP 1 — Remove stub from ChatMemoryWriter
═══════════════════════════════════════════════════════════════════════════

File: arcclaw-core/.../loop/ChatMemoryWriter.java

REMOVE the entire write() method that was added in Phase 71:

  // DELETE this entire block:
  /**
   * Persists updated history to ChatMemoryStore.
   * Required by Phase 71 parallel batch completion.
   */
  public void write(String sessionId,
                    List<com.arcclaw.core.agent.reasoning.ReasoningStep> history,
                    String goal) {
      // Stub implementation for compilation...
  }

ChatMemoryWriter after this change has exactly these three methods:
  - saveGoal(String sessionId, String goal)
  - saveAnswer(String sessionId, String answer)
  - clearMemory(String sessionId)

═══════════════════════════════════════════════════════════════════════════
STEP 2 — Remove ChatMemoryWriter from ParallelToolStepExecutor
═══════════════════════════════════════════════════════════════════════════

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java

2a. REMOVE the @Inject field:
  // DELETE:
  @Inject ChatMemoryWriter chatMemoryWriter;

2b. REMOVE the chatMemoryWriter.write() call from
    buildCompletedBatchDecision():

  // DELETE this entire try-catch block:
  try {
      chatMemoryWriter.write(
          context.sessionId(), history, context.goal());
  } catch (Exception e) {
      log.warnf("[PARALLEL] session=%s: ChatMemoryWriter.write() failed ...",
                context.sessionId(), e.getMessage());
  }

  // REPLACE with this comment explaining why no write is needed:
  // §7.14 (corrected): ReasoningStep history is in-memory during execution.
  // AgentLoop.runWithRole() persists to SQLite via agentStore.save() at
  // session end or HITL pause. ChatMemoryWriter handles user/AI messages
  // only — it does NOT manage tool-step history. No write needed here.

═══════════════════════════════════════════════════════════════════════════
STEP 3 — Correct §7.14 in Architecture_Invariants.md
═══════════════════════════════════════════════════════════════════════════

File: Architecture_Invariants.md

REPLACE the current §7.14:

  OLD:
    7.14 MUST: ChatMemoryWriter.write() MUST be called after every parallel
         batch execution completes (post-fold, pre-return). This keeps
         ChatMemoryStore (SQLite persistence) consistent with the in-memory
         ReasoningStep history list used by ChatHistoryAssembler.

  NEW:
    7.14 INVARIANT: ReasoningStep history is the in-memory source of truth
         during AgentLoop.runWithRole() execution. ChatHistoryAssembler reads
         from in-memory history on each LLM call — SQLite does not need to
         be current mid-run. AgentLoop persists history to SQLite via
         agentStore.save() at session end (Finished/Failed/LoopExceeded) or
         HITL pause (AwaitingApproval checkpoint). ChatMemoryWriter manages
         user goal/AI answer messages in ChatMemoryStore only — it MUST NOT
         be used for ReasoningStep history persistence.

═══════════════════════════════════════════════════════════════════════════
VERIFICATION
═══════════════════════════════════════════════════════════════════════════

After the three steps above:

  mvn clean verify -pl arcclaw-core

Expected: All tests pass. No ChatMemoryWriter.write() references remain
in ParallelToolStepExecutor. No stub method in ChatMemoryWriter.

Confirm the final ParallelToolStepExecutor @Inject fields are:
  @Inject ObjectMapper objectMapper;
  @Inject ToolInvocationGuard guard;
  @Inject ToolInvoker toolInvoker;
  @Inject ApprovalCheckpointManager checkpointManager;
  @Inject ToolObservationSanitizer observationSanitizer;
  @Inject Instance<AgentObserver> observers;
  @Inject GovernanceStrategy governanceLayer;
  @Inject RateLimitTracker rateLimitTracker;
  // chatMemoryWriter is gone

Phase 71 is complete after this change.
```

***

## P71 Final Status

| Component | Status |
|---|---|
| A1 ExecutorService close | ✅ Correct |
| A2 OTel context propagation | ✅ Correct (`Scope _` pattern, JDK 25) |
| A3 Remove `synchronized(history)` | ✅ Both sites cleaned |
| A4 ChatMemoryWriter after batch | ❌ **Stub is a no-op — remove entirely** |
| B1 Partial dedup | ✅ Pass 1/2 + `buildCompletedBatchDecision()` |
| B2 Per-tool timeout | ✅ `TimeoutException` + `@ConfigProperty` |
| C1 Metrics `PARALLEL_SENTINEL` guard | ✅ Correct |
| C2 MDC keys in `finally` | ✅ Correct |
| D1 Static `PARAMS_MAPPER` | ✅ Correct |
| D2 `channelType()` audit + §1.5 | ✅ Correct |
| D3 §7.12–§7.18 invariants | ⚠️ §7.14 needs correction per above |

Three files changed, 3 additions, ~15 deletions. Clean finish. 