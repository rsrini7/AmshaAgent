=====
PHASE 70 — Parallel Tool Execution
ArcClaw P69 → P70
=====

Role: Senior Java Architect & Senior Java Developer
Task: Implement full parallel tool execution for ArcClaw.
Phase 69-B component B1 added WARN logging for dropped parallel calls.
Phase 70 executes ALL parallel tool calls issued by the LLM in one
iteration, folds all observations back before the next LLM call, and
preserves HITL, deduplication, governance, and OTel correctness.

Architecture Context:
  Refer @Architecture_Invariants.md (mandatory).
  Phase 70 adds §7 to Architecture_Invariants.md (defined at end of this
  prompt — add it BEFORE writing any code).

Guiding Design Principles for Phase 70:
  1. ReasoningStep record MUST NOT change shape — it is persisted in
     SQLite via ReasoningStepEntity. A DB migration for ReasoningStep
     is out of scope for Phase 70.
  2. Represent a parallel LLM turn as a single ReasoningStep in history
     using a new sentinel action "__parallel__". The batch payload
     (all tool calls + all observations after execution) lives in
     actionInput as a JSON-serialized structure.
  3. ChatHistoryAssembler reconstructs the correct LangChain4j parallel
     protocol from the batch step: ONE AiMessage with N ToolExecutionRequests
     followed by N ToolExecutionResultMessages. This is the protocol
     Claude 3.5, GPT-4o, and Gemini 2.0 expect.
  4. If ANY tool in a parallel batch requires HITL approval, ALL tools
     in the batch pause atomically. Resume executes the full batch.
     Partial per-tool approval is Phase 71+.
  5. Parallel execution uses the existing ManagedExecutor — no new
     threading dependency.

Common Requirements:
  - All 877+ existing tests must remain green.
  - No new Maven dependencies.
  - mvn clean verify -pl <module> after each component.
  - static final Pattern for any new regex.
  - JDK 25, no preview features.
  - Add Architecture_Invariants.md §7 entries before writing code.


=====
PROMPT — PHASE 70-A: Core Data Structures & Parser
=====

------------
Step 0 — Add §7 to Architecture_Invariants.md BEFORE any code
------------

Add the following section to Architecture_Invariants.md:

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

  7.4 MUST: If ANY tool in a parallel batch requires HITL, ALL tools in
      the batch pause atomically. Partial per-tool approval is forbidden
      until Phase 71 defines the partial approval protocol.

  7.5 MUST NOT: Execute parallel tools on the calling thread.
      Use ManagedExecutor (already injected via WorkspaceExecutors)
      for all parallel tool invocations.

  7.6 MUST: All parallel tool spans MUST be siblings under the same
      parent iteration span. Use startToolSpan() per tool call.

  7.7 MUST: Deduplication guard (ToolInvocationGuard) MUST check ALL
      tools in a batch before ANY tool executes. If any call is a
      duplicate, the entire batch is evaluated before execution begins.

------------
Component 70-A1 — Add PARALLEL_SENTINEL to ReasoningStep
------------

File: arcclaw-core/.../reasoning/ReasoningStep.java

CURRENT: ReasoningStep has FINISH and OBSERVE sentinels only.

ADD (alongside existing sentinels):
  /**
   * Sentinel action for a parallel LLM turn (N tool calls in one response).
   * actionInput.get("batch") contains the JSON-serialized List<ParallelToolCall>
   * with fields: callId, toolName, params (pre-execution), observation (post-execution).
   * See Architecture_Invariants.md §7.1.
   */
  public static final String PARALLEL_SENTINEL = "__parallel__";

  /** Convenience: true if this step represents a parallel tool batch. */
  public boolean isParallelBatch() {
      return PARALLEL_SENTINEL.equals(this.action);
  }

ADD factory method for creating a batch step BEFORE execution:
  /**
   * Creates a pre-execution parallel batch step.
   * observation is empty until ParallelToolStepExecutor completes.
   */
  public static ReasoningStep parallelBatch(
          String thought,
          Map<String, Object> batchPayload,
          int inputTokens,
          int outputTokens) {
      return new ReasoningStep(
          thought,
          PARALLEL_SENTINEL,
          batchPayload,
          null,          // observation filled in after execution
          inputTokens,
          outputTokens
      );
  }

TEST: ReasoningStepParallelSentinelTest
  - new step with action=PARALLEL_SENTINEL → isParallelBatch() == true
  - new step with action="github" → isParallelBatch() == false
  - parallelBatch("thought", map, 100, 50).isParallelBatch() == true
  - withObservation("obs") on a parallel step preserves all other fields

------------
Component 70-A2 — New ParallelToolCall Record
------------

File: arcclaw-core/.../reasoning/ParallelToolCall.java (NEW)

CREATE:
  package com.arcclaw.core.agent.reasoning;

  import java.util.Map;

  /**
   * Represents one tool call within a parallel LLM response batch.
   * Immutable. Pre-execution: observation is null.
   * Post-execution: observation holds the sanitized ToolResponse output.
   *
   * Serialized to/from JSON via ObjectMapper as the "batch" key in
   * ReasoningStep.actionInput for Architecture_Invariants.md §7.1.
   */
  public record ParallelToolCall(
      String callId,                    // stable ID: "call_0", "call_1", ...
      String toolName,                  // LLM-requested tool name
      Map<String, Object> params,       // deserialized tool parameters
      String observation,               // null pre-execution; filled post-execution
      boolean success                   // false pre-execution; set post-execution
  ) {
      /** Creates a pre-execution call with null observation. */
      public static ParallelToolCall pending(
              String callId, String toolName, Map<String, Object> params) {
          return new ParallelToolCall(callId, toolName, params, null, false);
      }

      /** Returns a copy of this call with the execution result filled in. */
      public ParallelToolCall withResult(String observation, boolean success) {
          return new ParallelToolCall(
              this.callId, this.toolName, this.params, observation, success);
      }
  }

TEST: ParallelToolCallTest
  - pending(...).observation() == null, .success() == false
  - withResult("obs", true).observation() == "obs", .success() == true
  - Record is immutable — original unchanged after withResult()
  - Jackson serialization round-trip preserves all fields including null observation

------------
Component 70-A3 — Update ReasoningStepParser for Parallel Detection
------------

File: arcclaw-core/.../reasoning/ReasoningStepParser.java

CURRENT (Phase 69-B B1 — WARN log was added, getFirst() still used):
  List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
  if (requests.size() > 1) {
      log.warnf("[PARALLEL-TOOLS] session=%s role=%s: LLM issued %d parallel " +
                "tool calls. Only first ('%s') will execute. ...",
                sessionId, agentRole, requests.size(),
                requests.getFirst().name(), requests.size());
  }
  ToolExecutionRequest req = requests.getFirst();

REPLACE THE ENTIRE multi-tool handling block:

  Inject ObjectMapper objectMapper;  // Jackson — already in CDI context

  // In parse():
  List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();

  if (requests.size() == 1) {
      // — existing single-tool path, unchanged —
      ToolExecutionRequest req = requests.getFirst();
      log.infof("LLM is attempting to call tool %s with args: %s",
                req.name(), req.arguments());
      // ... existing JSON deserialization → ReasoningStep ...
  }

  // NEW: Parallel tool path
  if (requests.size() > 1) {
      log.infof("[PARALLEL-TOOLS] session=%s role=%s: LLM issued %d parallel " +
                "tool calls: %s — routing to ParallelToolStepExecutor.",
                sessionId, agentRole, requests.size(),
                requests.stream()
                    .map(ToolExecutionRequest::name)
                    .collect(java.util.stream.Collectors.joining(", ")));

      List<ParallelToolCall> calls = new ArrayList<>();
      for (int i = 0; i < requests.size(); i++) {
          ToolExecutionRequest req = requests.get(i);
          String callId = "call_" + i;
          Map<String, Object> params = deserializeParams(req.arguments());
          calls.add(ParallelToolCall.pending(callId, req.name(), params));
      }

      // Encode batch into actionInput for ReasoningStep (§7.1)
      Map<String, Object> batchPayload;
      try {
          String batchJson = objectMapper.writeValueAsString(calls);
          batchPayload = Map.of("batch", batchJson);
      } catch (Exception e) {
          log.errorf("Failed to serialize parallel batch for session %s: %s",
                     sessionId, e.getMessage());
          // Fallback: execute only first tool to prevent silent data loss
          log.warnf("[PARALLEL-TOOLS] Serialization failure — " +
                    "falling back to first tool '%s' only.", requests.getFirst().name());
          // ... existing single-tool path with getFirst() ...
      }

      int inputTokens  = response.tokenUsage() != null
                         ? response.tokenUsage().inputTokenCount() : 0;
      int outputTokens = response.tokenUsage() != null
                         ? response.tokenUsage().outputTokenCount() : 0;

      return ReasoningStep.parallelBatch(
          aiMessage.text(),   // LLM thought
          batchPayload,
          inputTokens,
          outputTokens
      );
  }

ADD private helper (extracted from existing single-tool param parsing):
  private Map<String, Object> deserializeParams(String argsJson) {
      if (argsJson == null || argsJson.isBlank()) return Map.of();
      try {
          @SuppressWarnings("unchecked")
          Map<String, Object> parsed =
              objectMapper.readValue(argsJson, Map.class);
          return parsed != null ? parsed : Map.of();
      } catch (Exception e) {
          log.warnf("Failed to parse tool arguments JSON: %s", e.getMessage());
          return Map.of("parameters", argsJson);
      }
  }

TEST: ReasoningStepParserParallelTest
  - ChatResponse with 1 tool call → single ReasoningStep (action = toolName)
  - ChatResponse with 3 tool calls → ReasoningStep.isParallelBatch() == true
  - Batch step actionInput "batch" key → deserializes to List<ParallelToolCall>
    with correct callIds "call_0", "call_1", "call_2"
  - ChatResponse with FINISH (no tool calls) → action = "FINISH" (unchanged)
  - Jackson round-trip of batch JSON preserves all tool names and params
  - Serialization failure → fallback to first tool, WARN logged


=====
PROMPT — PHASE 70-B: Parallel Execution Engine
=====

------------
Component 70-B1 — New ParallelToolStepExecutor
------------

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java (NEW)

This is the central new class for Phase 70.
It handles the full lifecycle of a parallel batch step:
  validate → HITL check → parallel invoke → observation fold-back

CREATE:
  package com.arcclaw.core.agent.loop;

  import com.arcclaw.core.agent.loop.AgentStateMachineRunner.StepDecision;
  import com.arcclaw.core.agent.reasoning.ParallelToolCall;
  import com.arcclaw.core.agent.reasoning.ReasoningStep;
  import com.arcclaw.core.agent.result.AgentResult;
  import com.arcclaw.core.observability.AgentObserver;
  import com.arcclaw.core.scope.WorkspaceExecutors;
  import com.arcclaw.core.tools.ToolResponse;
  import com.fasterxml.jackson.core.type.TypeReference;
  import com.fasterxml.jackson.databind.ObjectMapper;
  import jakarta.enterprise.context.ApplicationScoped;
  import jakarta.enterprise.inject.Instance;
  import jakarta.inject.Inject;
  import org.eclipse.microprofile.context.ManagedExecutor;
  import org.jboss.logging.Logger;

  import java.util.*;
  import java.util.concurrent.CompletableFuture;
  import java.util.concurrent.ExecutionException;
  import java.util.stream.Collectors;

  /**
   * Executes a parallel tool batch produced by ReasoningStepParser
   * when the LLM issues N > 1 tool calls in one response.
   *
   * Protocol (Architecture_Invariants.md §7):
   *   1. Validate all tools in batch via ToolInvocationGuard.
   *   2. If ANY requires HITL → pause ALL via ApprovalCheckpointManager.
   *   3. Execute all allowed tools in parallel via ManagedExecutor.
   *   4. Fold all observations back into one completed ReasoningStep.
   *   5. Add that step to history for ChatHistoryAssembler to reconstruct.
   */
  @ApplicationScoped
  public class ParallelToolStepExecutor {

      private static final Logger log =
          Logger.getLogger(ParallelToolStepExecutor.class);

      @Inject ObjectMapper objectMapper;
      @Inject ToolInvocationGuard guard;
      @Inject ToolInvoker toolInvoker;
      @Inject ApprovalCheckpointManager checkpointManager;
      @Inject ToolObservationSanitizer observationSanitizer;
      @Inject WorkspaceExecutors workspaceExecutors;
      @Inject Instance<AgentObserver> observers;

      private static final TypeReference<List<ParallelToolCall>> BATCH_TYPE =
          new TypeReference<>() {};

      /**
       * Entry point. Called by ToolStepHandler when step.isParallelBatch() == true.
       */
      public StepDecision execute(
              AgentExecutionContext context,
              List<ReasoningStep> history,
              ReasoningStep batchStep,
              int consecutiveFailures) {

          // 1. Deserialize batch
          List<ParallelToolCall> calls = deserializeBatch(batchStep, context.sessionId());
          if (calls == null || calls.isEmpty()) {
              log.errorf("[PARALLEL] session=%s: empty or corrupt batch — terminating.",
                         context.sessionId());
              return StepDecision.terminal(AgentResult.failed(
                  "Parallel tool batch was empty or corrupt.",
                  AgentResult.FailureCategory.SYSTEM_ERROR,
                  List.copyOf(history)));
          }

          log.infof("[PARALLEL] session=%s: executing %d tool calls: %s",
                    context.sessionId(), calls.size(),
                    calls.stream().map(ParallelToolCall::toolName)
                         .collect(Collectors.joining(", ")));

          // 2. Validate all tools (deduplication + HITL check) — §7.7 BEFORE execution
          for (ParallelToolCall call : calls) {
              ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                  context.sessionId(), context.agentRole(),
                  call.toolName(), call.params(), history,
                  context.goal(), List.of()); // skillSnapshot: pass if available

              if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval pause) {
                  // §7.4: ANY tool needs HITL → pause ALL atomically
                  log.infof("[PARALLEL-HITL] session=%s tool=%s requires approval " +
                            "— pausing entire batch of %d tools.",
                            context.sessionId(), call.toolName(), calls.size());
                  return pauseEntireBatch(context, history, batchStep, calls, pause);
              }

              if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked blocked) {
                  // Blocked tool in batch → fail the entire batch for safety
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
                  // Duplicate in batch — surface cached observation, skip entire batch
                  log.warnf("[PARALLEL-DEDUP] session=%s tool=%s is duplicate — " +
                            "using cached observation, skipping full batch.",
                             context.sessionId(), call.toolName());
                  ReasoningStep dedupStep = batchStep.withObservation(
                      "[PARALLEL-DEDUP] " + dedup.cachedObservation());
                  history.add(dedupStep);
                  return StepDecision.proceed(dedupStep);
              }
              // GuardDecision.Allow → continue to next tool
          }

          // 3. Execute all tools in parallel via ManagedExecutor (§7.5)
          ManagedExecutor executor = workspaceExecutors.managedExecutor();
          List<CompletableFuture<ParallelToolCall>> futures = calls.stream()
              .map(call -> CompletableFuture.supplyAsync(() -> {
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

          // 4. Collect all results (wait for all — §7.5)
          List<ParallelToolCall> completed = new ArrayList<>();
          for (CompletableFuture<ParallelToolCall> future : futures) {
              try {
                  completed.add(future.get());
              } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return StepDecision.terminal(AgentResult.failed(
                      "Parallel tool execution interrupted.",
                      AgentResult.FailureCategory.SYSTEM_ERROR,
                      List.copyOf(history)));
              } catch (ExecutionException e) {
                  // Individual tool failures are already captured in withResult()
                  // ExecutionException here means the CompletableFuture itself failed
                  log.errorf("[PARALLEL-EXEC] Unexpected future failure: %s",
                             e.getCause() != null ? e.getCause().getMessage()
                                                  : e.getMessage());
                  completed.add(calls.get(futures.indexOf(future))
                      .withResult("Unexpected execution failure.", false));
              }
          }

          // 5. Fold all results back into the batch step (§7.1, §7.2)
          String batchJson;
          try {
              batchJson = objectMapper.writeValueAsString(completed);
          } catch (Exception e) {
              log.errorf("[PARALLEL] Failed to serialize completed batch: %s",
                          e.getMessage());
              return StepDecision.terminal(AgentResult.failed(
                  "Failed to serialize parallel tool results.",
                  AgentResult.FailureCategory.SYSTEM_ERROR,
                  List.copyOf(history)));
          }

          Map<String, Object> completedPayload = new HashMap<>();
          completedPayload.put("batch", batchJson);

          // Build summary observation for logging and guard checks
          String summary = completed.stream()
              .map(c -> String.format("[%s → %s]",
                  c.toolName(),
                  c.success() ? "OK" : "FAIL"))
              .collect(Collectors.joining(", "));

          log.infof("[PARALLEL] session=%s batch completed: %s", context.sessionId(), summary);

          // Replace actionInput with completed batch (includes observations)
          // Use withObservation for the summary (history line) + completed payload
          ReasoningStep completedBatchStep = new ReasoningStep(
              batchStep.thought(),
              ReasoningStep.PARALLEL_SENTINEL,
              completedPayload,
              summary,                              // brief observation for history scan
              batchStep.inputTokens(),
              batchStep.outputTokens()
          );

          history.add(completedBatchStep);

          // Notify observers (one per tool call)
          for (ParallelToolCall c : completed) {
              ReasoningStep toolStep = new ReasoningStep(
                  null, c.toolName(), c.params(), c.observation(), 0, 0);
              observers.forEach(o -> o.onStepComplete(
                  context.sessionId(), context.agentRole(), toolStep));
          }

          return StepDecision.proceed(completedBatchStep);
      }

      /**
       * Pauses the entire batch for HITL approval.
       * Saves the full batch JSON to the checkpoint so resume
       * can replay it. See Architecture_Invariants.md §7.4.
       */
      private StepDecision pauseEntireBatch(
              AgentExecutionContext context,
              List<ReasoningStep> history,
              ReasoningStep batchStep,
              List<ParallelToolCall> calls,
              ToolInvocationGuard.GuardDecision.PauseForApproval pause) {

          String batchJson;
          try {
              batchJson = objectMapper.writeValueAsString(calls);
          } catch (Exception e) {
              return StepDecision.terminal(AgentResult.failed(
                  "Failed to serialize batch for HITL approval.",
                  AgentResult.FailureCategory.SYSTEM_ERROR,
                  List.copyOf(history)));
          }

          // Reuse ApprovalCheckpointManager with a composite tool name
          String compositeName = "__parallel__:" +
              calls.stream().map(ParallelToolCall::toolName)
                   .collect(Collectors.joining(","));

          return checkpointManager.persist(context, compositeName, batchJson, history);
      }

      private List<ParallelToolCall> deserializeBatch(
              ReasoningStep batchStep, String sessionId) {
          try {
              Object batchRaw = batchStep.actionInput() != null
                  ? batchStep.actionInput().get("batch") : null;
              if (batchRaw == null) return null;
              String batchJson = batchRaw instanceof String s ? s
                  : objectMapper.writeValueAsString(batchRaw);
              return objectMapper.readValue(batchJson, BATCH_TYPE);
          } catch (Exception e) {
              log.errorf("[PARALLEL] session=%s: batch deserialization failed: %s",
                         sessionId, e.getMessage());
              return null;
          }
      }
  }

TEST: ParallelToolStepExecutorTest
  - 3 allowed tools → all 3 invoke() called, 3 observations in completed batch.
  - 1 of 3 tools returns PauseForApproval → checkpointManager.persist() called,
    compositeName starts with "__parallel__:", batch JSON in paramsJson.
  - 1 of 3 tools returns Blocked → history gets blocked obs, retryValidation returned.
  - Tool #2 throws RuntimeException → withResult("Tool execution failed: ...", false),
    other tools still succeed, batch completes.
  - InterruptedException → Thread.interrupted == true after return.
  - 3 observers notified (one onStepComplete per tool).

------------
Component 70-B2 — Route Parallel Steps in ToolStepHandler
------------

File: arcclaw-core/.../loop/ToolStepHandler.java

ADD injection of ParallelToolStepExecutor:
  @Inject ParallelToolStepExecutor parallelExecutor;

MODIFY validateTool() — add parallel batch fast-path at the TOP:
  public StepDecision validateTool(
          AgentExecutionContext context,
          List<ReasoningStep> history,
          ReasoningStep step,
          int consecutiveFailures) {

      // Parallel batch — bypass single-tool validation, route to executor
      if (step.isParallelBatch()) {
          log.debugf("ToolStepHandler routing parallel batch to " +
                     "ParallelToolStepExecutor for session %s", context.sessionId());
          return StepDecision.proceed(step);
          // Execution happens in invokeToolAndObserve()
      }

      // ... existing single-tool validation unchanged below ...
  }

MODIFY invokeToolAndObserve() — add parallel batch fast-path at the TOP:
  public StepDecision invokeToolAndObserve(
          AgentExecutionContext context,
          List<ReasoningStep> history,
          ReasoningStep step,
          int consecutiveFailures,
          List<CompiledSkillEntry> skillSnapshot) {

      // Parallel batch — delegate to ParallelToolStepExecutor
      if (step.isParallelBatch()) {
          return parallelExecutor.execute(context, history, step, consecutiveFailures);
      }

      // ... existing single-tool execution unchanged below ...
  }

TEST: ToolStepHandlerParallelRoutingTest
  - step.isParallelBatch() == true → parallelExecutor.execute() called.
  - step.isParallelBatch() == false → existing single-tool path (regression).
  - parallelExecutor is NOT called for FINISH or OBSERVE steps.

------------
Component 70-B3 — Update AgentStateMachineRunner StepDecision for Batch
──────────────────────────────────────────════════════════════════════════

File: arcclaw-core/.../loop/AgentStateMachineRunner.java

NO structural changes needed. The existing PROCEED/CONTINUE/TERMINAL
routing already handles the StepDecision returned by ParallelToolStepExecutor.

VERIFY the following invariant holds (add comment):
  // Phase 70 note: parallel batch steps return StepDecision.proceed()
  // from ParallelToolStepExecutor. The step in the decision is the
  // completed batch step (with all observations). History was already
  // mutated by ParallelToolStepExecutor. The runner continues to the
  // next LLM call normally. No structural runner change needed.

ADD a guard in the runner's invokeToolAndObserve call site to log
if a parallel batch step somehow reaches the FINISH action handler:
  if (ReasoningStep.FINISH.equals(step.action()) && step.isParallelBatch()) {
      log.errorf("[PARALLEL] INVARIANT VIOLATION: session=%s parallel batch " +
                 "step reached FINISH handler — this is a bug in Phase 70.",
                 context.sessionId());
  }

TEST: All existing AgentStateMachineRunnerTest tests pass unchanged (regression).


=====
PROMPT — PHASE 70-C: Chat History Protocol & HITL Parallel Resume
=====

------------
Component 70-C1 — Update ChatHistoryAssembler for Parallel Protocol
------------

File: arcclaw-core/.../reasoning/ChatHistoryAssembler.java

PROBLEM: ChatHistoryAssembler currently handles each ReasoningStep as a
single tool call:
  messages.add(AiMessage.aiMessage(thought, Collections.singletonList(request)));
  messages.add(ToolExecutionResultMessage.toolExecutionResultMessage(
      callId, toolName, observation));

For a parallel batch step, the LangChain4j protocol requires:
  ONE AiMessage with ALL N ToolExecutionRequests
  followed by N ToolExecutionResultMessages in callId order.
This is what Claude 3.5, GPT-4o, Gemini 2.0 expect on the next turn.

INJECT ObjectMapper (add if not already present):
  @Inject ObjectMapper objectMapper;

ADD parallel batch handling in the STEP B ReAct loop:

  // Inside: for (int i = 0; i < history.size(); i++) { ... }
  ReasoningStep step = history.get(i);

  // NEW: Handle parallel batch step (§7.3)
  if (step.isParallelBatch()) {
      List<ParallelToolCall> calls = deserializeBatch(step);
      if (calls != null && !calls.isEmpty()) {
          // Build N ToolExecutionRequests for ONE AiMessage
          List<ToolExecutionRequest> allRequests = calls.stream()
              .map(call -> {
                  String argsJson;
                  try {
                      argsJson = objectMapper.writeValueAsString(call.params());
                  } catch (Exception e) {
                      argsJson = "{}";
                  }
                  return ToolExecutionRequest.builder()
                      .id(call.callId())
                      .name(call.toolName())
                      .arguments(argsJson)
                      .build();
              })
              .toList();

          // ONE AiMessage with ALL N tool requests (LangChain4j parallel protocol)
          messages.add(AiMessage.aiMessage(
              step.thought() != null ? step.thought() : "",
              allRequests));

          // N ToolExecutionResultMessages — one per callId in order
          for (ParallelToolCall call : calls) {
              String safeObs = call.observation() != null ? call.observation() : "";
              if (safeObs.length() > 4000) safeObs = safeObs.substring(0, 4000) + "...";
              messages.add(ToolExecutionResultMessage.toolExecutionResultMessage(
                  call.callId(), call.toolName(), safeObs));
          }
      } else {
          // Deserialization failed — add error as plain AI message to preserve
          // context continuity
          log.warnf("ChatHistoryAssembler: failed to deserialize parallel batch " +
                    "at history index %d — adding error placeholder.", i);
          messages.add(AiMessage.aiMessage(
              "[PARALLEL-BATCH-ERROR: could not reconstruct tool calls]"));
      }
      continue;  // skip the single-tool path below
  }

  // ... existing single-tool path unchanged ...

ADD private helper:
  private List<ParallelToolCall> deserializeBatch(ReasoningStep step) {
      try {
          Object raw = step.actionInput() != null
              ? step.actionInput().get("batch") : null;
          if (raw == null) return null;
          String json = raw instanceof String s ? s
              : objectMapper.writeValueAsString(raw);
          return objectMapper.readValue(json,
              new com.fasterxml.jackson.core.type.TypeReference<
                  List<ParallelToolCall>>() {});
      } catch (Exception e) {
          log.warnf("ChatHistoryAssembler.deserializeBatch failed: %s",
                    e.getMessage());
          return null;
      }
  }

TEST: ChatHistoryAssemblerParallelProtocolTest
  - History with 1 parallel batch step (3 tools) →
    assemble() produces:
      messages[0] = SystemMessage
      messages  = AiMessage with 3 ToolExecutionRequests
      messages[2] = ToolExecutionResultMessage for call_0
      messages[3] = ToolExecutionResultMessage for call_1
      messages[4] = ToolExecutionResultMessage for call_2
  - observations > 4000 chars truncated with "..."
  - null observation → empty string (no NPE)
  - Mixed history: [singleTool, parallelBatch, singleTool] →
    correct 3-segment message sequence
  - Corrupt batch JSON → error placeholder AiMessage, no exception

------------
Component 70-C2 — Update AgentCheckpoint for Parallel HITL Resume
------------

File: arcclaw-core/.../state/AgentCheckpoint.java (entity)

PROBLEM: AgentCheckpoint stores a single pendingToolName and
pendingToolParametersJson. For a parallel batch pause (§7.4),
pendingToolName is set to "__parallel__:tool1,tool2,tool3" and
pendingToolParametersJson holds the full batch JSON.

This encoding works WITHOUT a schema migration — the composite name
and JSON array fit in the existing TEXT columns. However, the resume
handler needs to detect and handle batch checkpoints.

NO schema change needed. ADD a helper method to AgentCheckpoint:
  /**
   * Returns true if this checkpoint represents a paused parallel batch.
   * See Architecture_Invariants.md §7.4.
   */
  public boolean isParallelBatch() {
      return pendingToolName != null
          && pendingToolName.startsWith("__parallel__:");
  }

  /**
   * Returns the individual tool names for a parallel batch checkpoint.
   * Returns empty list for single-tool checkpoints.
   */
  public List<String> parallelToolNames() {
      if (!isParallelBatch()) return List.of();
      String composite = pendingToolName.substring("__parallel__:".length());
      return Arrays.asList(composite.split(","));
  }

------------
Component 70-C3 — Update CheckpointResumeHandler for Parallel Batch
------------

File: arcclaw-core/.../loop/CheckpointResumeHandler.java

PROBLEM: The resume handler calls:
  toolRegistry.invokeTool(pendingTool, pendingParams, sessionId)
for a single tool. A parallel batch checkpoint has pendingTool =
"__parallel__:github,memory_search,manage_schedule" and pendingParams
= JSON array of ParallelToolCall. This must replay all N tools.

MODIFY handle() — add parallel batch detection AFTER the isDenial check:

  // After: String pendingTool = checkpoint.getPendingToolName();
  // After: String pendingParams = checkpoint.getPendingToolParametersJson();
  // After: boolean isDenial check (Phase 69-A A3 fix)...

  // NEW: Parallel batch resume (§7.4)
  if (checkpoint.isParallelBatch()) {
      return resumeParallelBatch(context, history, checkpoint);
  }

  // ... existing single-tool resume path unchanged below ...

ADD private resumeParallelBatch():
  private AgentResult resumeParallelBatch(
          AgentExecutionContext context,
          List<ReasoningStep> history,
          AgentCheckpoint checkpoint) {

      String sessionId = context.sessionId();
      String agentRole = context.agentRole();

      log.infof("[PARALLEL-HITL-RESUME] session=%s resuming batch: %s",
                sessionId, checkpoint.parallelToolNames());

      // Parse the saved batch
      List<ParallelToolCall> calls;
      try {
          calls = objectMapper.readValue(
              checkpoint.getPendingToolParametersJson(),
              new com.fasterxml.jackson.core.type.TypeReference<
                  List<ParallelToolCall>>() {});
      } catch (Exception e) {
          log.errorf("[PARALLEL-HITL-RESUME] session=%s: failed to parse batch: %s",
                     sessionId, e.getMessage());
          checkpoint.setStatus(CheckpointStatus.FAILED);
          agentStore.save(checkpoint);
          return AgentResult.failed(
              "Failed to resume parallel tool batch — corrupt checkpoint.",
              AgentResult.FailureCategory.SYSTEM_ERROR,
              List.copyOf(history));
      }

      // Execute ALL tools in the approved batch via full autonomy
      // (user approved the whole batch — §7.4 atomicity)
      List<ParallelToolCall> completed = new ArrayList<>();
      for (ParallelToolCall call : calls) {
          try {
              ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                  sessionId,
                  WorkspaceContext.userId(),
                  WorkspaceContext.chatId(),
                  agentRole,
                  () -> toolRegistry.invokeTool(
                      call.toolName(), call.paramsAsString(), sessionId));
              completed.add(call.withResult(response.output(), response.success()));
          } catch (Exception e) {
              log.warnf("[PARALLEL-HITL-RESUME] session=%s tool=%s failed: %s",
                        sessionId, call.toolName(), e.getMessage());
              completed.add(call.withResult(
                  "Tool execution failed on resume: " + e.getMessage(), false));
          }
      }

      // Fold completed calls back into history as a batch step
      String completedJson;
      try {
          completedJson = objectMapper.writeValueAsString(completed);
      } catch (Exception e) {
          checkpoint.setStatus(CheckpointStatus.FAILED);
          agentStore.save(checkpoint);
          return AgentResult.failed(
              "Failed to serialize resumed batch results.",
              AgentResult.FailureCategory.SYSTEM_ERROR,
              List.copyOf(history));
      }

      String summary = completed.stream()
          .map(c -> c.toolName() + "→" + (c.success() ? "OK" : "FAIL"))
          .collect(java.util.stream.Collectors.joining(", "));

      ReasoningStep completedStep = new ReasoningStep(
          "Human Approved (batch)",
          ReasoningStep.PARALLEL_SENTINEL,
          Map.of("batch", completedJson),
          summary,
          0, 0
      );

      synchronized (history) {
          history.add(completedStep);
      }

      checkpoint.setPendingToolName(null);
      checkpoint.setPendingToolParametersJson(null);
      checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
      agentStore.save(checkpoint);

      return null; // Signal main loop to continue to next LLM call
  }

TEST: CheckpointResumeHandlerParallelTest
  - Checkpoint with pendingToolName="__parallel__:github,memory_search"
    and pendingParams = valid batch JSON →
    both tools invoked, completed batch ReasoningStep added to history,
    checkpoint status = IN_PROGRESS, null returned (loop continues).
  - Corrupt pendingParams JSON → AgentResult.failed with SYSTEM_ERROR.
  - Tool #2 throws on resume → withResult(..., false), other tool completes.
  - Single-tool checkpoint (no __parallel__ prefix) → existing path unchanged.


=====
PROMPT — PHASE 70-D: Governance, OTel, Dedup & Invariant Compliance
=====

------------
Component 70-D1 — OTel: Parallel Sibling Spans (§7.6)
------------

File: arcclaw-core/.../observability/AgentOtelContext.java

CURRENT: startToolSpan(sessionId, toolName, "invoke") creates one span.

For parallel execution, ParallelToolStepExecutor calls startToolSpan()
once per tool — this already creates N sibling spans under the same
parent iteration span. NO code change needed in AgentOtelContext.

VERIFY the parent span relationship is correct:
  - AgentStateMachineRunner creates the iteration span: iterSpan
  - Each toolInvoker.invoke() call inside ParallelToolStepExecutor
    runs on a ManagedExecutor thread. The span context MUST propagate.

ENSURE WorkspaceExecutors.managedExecutor() is the CDI ManagedExecutor
with context propagation enabled (it is — confirmed via MicroProfile
Context Propagation in Quarkus). OpenTelemetry context propagates
automatically.

ADD a log assertion for verifying span propagation in test:
  log.debugf("[PARALLEL-OTEL] session=%s starting %d parallel tool spans",
             sessionId, calls.size());

In ParallelToolStepExecutor.execute() BEFORE the CompletableFuture map:
  log.debugf("[PARALLEL-OTEL] session=%s executing %d tools in parallel: %s",
             context.sessionId(), calls.size(),
             calls.stream().map(ParallelToolCall::toolName)
                  .collect(Collectors.joining(", ")));

TEST: ParallelToolStepExecutorOtelTest
  - Verify N tool spans created (via mock AgentOtelContext counting calls)
  - Verify spans are NOT null (context propagated to ManagedExecutor threads)

------------
Component 70-D2 — Governance: Budget Recording for Parallel Batch
------------

File: arcclaw-core/.../agent/reasoning/LangChainReasoningEngine.java

CURRENT: governanceLayer.recordLlmCall(sessionId, inputTokens, outputTokens)
is called ONCE per LLM response, which is correct regardless of how many
tool calls the LLM issued. The token count is for the LLM call, not for
tool executions.

NO change needed to token accounting — budget is per LLM call.

HOWEVER: Add a governance pre-flight check in ParallelToolStepExecutor
BEFORE executing tools. If the session is already at 95%+ budget,
executing expensive tools (e.g. github + memory_search + ingest_document)
could trigger BudgetExceededException mid-execution for some tools:

ADD to ParallelToolStepExecutor.execute() BEFORE the futures map:
  // Pre-flight budget check: abort batch before ANY tool runs if
  // budget is already exceeded (defensive — governance records on LLM call,
  // but tool execution can be long-running)
  try {
      // Use 0 tokens to trigger only the BudgetExceededException check
      // (not BudgetWarningException — we still proceed with warnings)
      governanceLayer.assertWithinBudget(context.sessionId());
  } catch (BudgetExceededException be) {
      log.warnf("[PARALLEL-BUDGET] session=%s batch aborted before execution: %s",
                context.sessionId(), be.getMessage());
      return StepDecision.terminal(AgentResult.failed(
          AgentErrorMessageUtil.sanitizeErrorMessage(be.getMessage()),
          AgentResult.FailureCategory.BUDGET_EXCEEDED,
          List.copyOf(history)));
  }

  NOTE: If GovernanceStrategy does not expose assertWithinBudget() as a
  public interface method, add it to GovernanceStrategy interface and
  implement in GovernanceLayer as a no-op if under budget.

Inject GovernanceStrategy into ParallelToolStepExecutor:
  @Inject GovernanceStrategy governanceLayer;

TEST: ParallelToolStepExecutorBudgetTest
  - Budget exceeded before batch runs → terminal with BUDGET_EXCEEDED,
    no tool invoked.
  - Budget warning (not exceeded) → batch proceeds normally.

------------
Component 70-D3 — RateLimitTracker: Pre-flight for Parallel Batch
------------

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java

PROBLEM: Parallel tool execution may involve tools backed by rate-limited
APIs. Running N tools simultaneously could exhaust rate limits faster.

ADD pre-flight check using Phase 69-B B6 per-provider tracker:
  // Inject (Phase 69-B B6 adds provider-aware tracking):
  @Inject RateLimitTracker rateLimitTracker;

ADD in execute() AFTER budget check, BEFORE futures map:
  // Per-provider pre-flight: warn if any provider is backing off
  // Tools themselves will handle rate limit exceptions independently
  if (rateLimitTracker.isAnyBackingOff()) {
      log.warnf("[PARALLEL-RATELIMIT] session=%s: at least one LLM provider " +
                "is in rate-limit backoff. Parallel tool batch of %d will proceed " +
                "but LLM-invoking tools may fail independently.",
                context.sessionId(), calls.size());
  }
  // NOTE: We proceed (not abort) because tools are not LLM calls.
  // The rate limit tracker guards LLM compaction, not tool execution.
  // Individual tool failures are captured in withResult(..., false).

------------
Component 70-D4 — Update SUSPICIOUSLY_SHORT Guard for Parallel Context
------------

File: arcclaw-core/.../agent/loop/AgentStateMachineRunner.java
      (or AgentLoop.java — wherever guardFinalAnswer lives)

PROBLEM: The SUSPICIOUSLY_SHORT Pattern fires on "Done." and "OK." answers.
After a parallel batch step, the LLM may legitimately respond with a
short acknowledgment like "All 3 tasks completed." (20 chars + space).
The current guard checks step.outputTokens which is always 0 for
ReasoningStep.pending paths. The pattern-only check should not
accidentally suppress valid short parallel-completion answers.

VERIFY the SUSPICIOUSLY_SHORT regex does NOT match:
  - "All 3 tasks completed."
  - "GitHub fetched, memory searched, document ingested."
  - "Done. All tools executed successfully."

The existing regex (guards against "Done." and "OK." and "Got it.") should
be correct. ADD test coverage to confirm:

TEST: GuardFinalAnswerParallelContextTest
  - guardFinalAnswer("Done.") → replaced with retry prompt (still suppressed)
  - guardFinalAnswer("All 3 tasks completed.") → returned as-is (NOT suppressed)
  - guardFinalAnswer("GitHub fetched, memory searched.") → returned as-is

------------
Component 70-D5 — Update Architecture_Invariants.md §7 (Final)
------------

Complete the §7 section added in Step 0 with the implementation notes:

  7.8 MUST: ParallelToolCall.callId MUST follow the pattern "call_N"
      where N is the 0-based index in the LLM's tool call list.
      Do NOT use UUID for callIds — LangChain4j requires stable,
      predictable callId matching between AiMessage and
      ToolExecutionResultMessage.

  7.9 MUST NOT: Modify ReasoningStep record shape in Phase 70.
      The parallel batch is encoded in actionInput["batch"] as JSON.
      Schema migration for a native parallel column is Phase 71+.

  7.10 SHOULD: Test parallel tool execution with Claude 3.5 Sonnet and
       GPT-4o before enabling in production, as parallel tool call
       behavior differs between providers. Groq (llama-3.x) rarely
       issues parallel tool calls — most parallel call traffic will
       come from Anthropic and OpenAI providers.

  7.11 LEGACY: Single getFirst() path in ReasoningStepParser is removed
       in Phase 70. Do NOT add it back as a fallback without updating
       this invariant first.


=====
PHASE 70 EXECUTION ORDER
=====

Execute components in this strict dependency order:

  70-A1  ReasoningStep.PARALLEL_SENTINEL          (no deps)
  70-A2  ParallelToolCall record                  (no deps)
  70-A3  ReasoningStepParser.parseBatch()         (needs A1, A2)
  70-B1  ParallelToolStepExecutor                 (needs A1, A2, A3)
  70-B2  ToolStepHandler routing                  (needs B1)
  70-B3  AgentStateMachineRunner guard comment     (needs B2)
  70-C1  ChatHistoryAssembler protocol            (needs A1, A2)
  70-C2  AgentCheckpoint.isParallelBatch()        (needs A2)
  70-C3  CheckpointResumeHandler parallel resume  (needs B1, C2)
  70-D1  OTel log assertion                       (needs B1)
  70-D2  Governance pre-flight in executor        (needs B1)
  70-D3  RateLimitTracker pre-flight              (needs B1, 69-B B6)
  70-D4  SUSPICIOUSLY_SHORT guard test            (no deps)
  70-D5  Architecture_Invariants.md §7 final     (needs all above)

After ALL components:
  mvn clean verify (full build — all modules)
  Assert: 877+ existing tests pass.
  Assert: All new Phase 70 tests added and green.


=====
KNOWN PHASE 70 LIMITATIONS (Tracked for Phase 71)
=====

  P71-1 — Partial HITL approval: Currently ALL tools in a parallel batch
           pause if ANY needs approval. Phase 71 should allow per-tool
           approval with a batch approval UI (approve tool #2, skip #3).

  P71-2 — Streaming parallel observations: Tools currently wait for ALL
           to complete before adding to history (allOf). Phase 71 could
           stream completed observations to the LLM as they arrive using
           anyOf + progressive history update.

  P71-3 — ReasoningStepEntity native parallel column: Phase 70 stores
           the batch as JSON in actionInput. Phase 71 should add a
           proper DB migration for a parallel_batch BOOLEAN flag and
           a separate parallel_calls TEXT column for clean querying.

  P71-4 — Per-tool timeout: All tools share the ManagedExecutor default
           timeout. Phase 71 should add per-tool timeout configuration
           via @ConfigProperty arcclaw.parallel.tool-timeout-seconds.

  P71-5 — Parallel tool result compaction: Compaction currently processes
           history as ReasoningStep.action per step. Parallel batch steps
           with embedded JSON need a dedicated compaction handler to
           avoid passing raw JSON to the compaction LLM prompt.

***

## Phase 70 At a Glance

| Component | File | New / Modified | Risk |
|---|---|---|---|
| A1 PARALLEL_SENTINEL | `ReasoningStep.java` | Modified | Low |
| A2 `ParallelToolCall` | `ParallelToolCall.java` | **New** | None |
| A3 Batch parser | `ReasoningStepParser.java` | Modified | Medium |
| B1 Batch executor | `ParallelToolStepExecutor.java` | **New** | High |
| B2 Route parallel | `ToolStepHandler.java` | Modified | Low |
| B3 Runner guard | `AgentStateMachineRunner.java` | Comment + guard | None |
| C1 History protocol | `ChatHistoryAssembler.java` | Modified | Medium |
| C2 Checkpoint detect | `AgentCheckpoint.java` | Modified | Low |
| C3 Parallel resume | `CheckpointResumeHandler.java` | Modified | High |
| D1 OTel log | `ParallelToolStepExecutor.java` | Modified | None |
| D2 Budget pre-flight | `ParallelToolStepExecutor.java` | Modified | Low |
| D3 RateLimit pre-flight | `ParallelToolStepExecutor.java` | Modified | None |
| D4 Guard test | `AgentStateMachineRunner.java` | Test only | None |
| D5 Invariants §7 | `Architecture_Invariants.md` | Modified | None |

Zero new Maven dependencies. Zero DB migrations. All 877+ existing tests unaffected.