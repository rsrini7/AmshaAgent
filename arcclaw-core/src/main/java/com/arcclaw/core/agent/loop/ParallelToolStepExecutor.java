package com.arcclaw.core.agent.loop;

import java.util.*;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.AgentStateMachineRunner.StepDecision;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.scope.WorkspaceExecutors;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.MdcKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Executes a parallel tool batch produced by ReasoningStepParser
 * when the LLM issues N > 1 tool calls in one response.
 */
@ApplicationScoped
public class ParallelToolStepExecutor {

    private static final Logger log = Logger.getLogger(ParallelToolStepExecutor.class);

    @Inject ObjectMapper objectMapper;
    @Inject ToolInvocationGuard guard;
    @Inject ToolInvoker toolInvoker;
    @Inject ApprovalCheckpointManager checkpointManager;
    @Inject ToolObservationSanitizer observationSanitizer;
    @Inject Instance<AgentObserver> observers;
    @Inject GovernanceStrategy governanceLayer;
    @Inject RateLimitTracker rateLimitTracker;


    @ConfigProperty(name = "arcclaw.parallel.tool-timeout-seconds", defaultValue = "60")
    long toolTimeoutSeconds;

    private static final TypeReference<List<ParallelToolCall>> BATCH_TYPE = new TypeReference<>() {};

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
            log.errorf("[PARALLEL] session=%s: empty or corrupt batch — terminating.", context.sessionId());
            return StepDecision.terminal(AgentResult.failed(
                "Parallel tool batch was empty or corrupt.",
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(history)));
        }

        log.infof("[PARALLEL] session=%s: executing %d tool calls: %s",
                  context.sessionId(), calls.size(),
                  calls.stream().map(ParallelToolCall::toolName)
                       .collect(Collectors.joining(", ")));

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

        try {
            governanceLayer.assertWithinBudget(context.sessionId());
        } catch (BudgetExceededException be) {
            log.warnf("[PARALLEL-BUDGET] session=%s batch aborted before execution: %s",
                      context.sessionId(), be.getMessage());
            return StepDecision.terminal(AgentResult.failed(
                AgentErrorMessageUtil.sanitizeErrorMessage(be.getMessage()),
                AgentResult.FailureCategory.BUDGET_EXCEEDED,
                List.copyOf(history)));
        }

        if (rateLimitTracker.isAnyBackingOff()) {
            log.warnf("[PARALLEL-RATELIMIT] session=%s: at least one LLM provider " +
                      "is in rate-limit backoff. Parallel tool batch of %d will proceed " +
                      "but LLM-invoking tools may fail independently.",
                      context.sessionId(), calls.size());
        }

        // 3. Execute all tools in parallel
        log.debugf("[PARALLEL-OTEL] session=%s executing %d tools in parallel: %s",
                   context.sessionId(), allowedCalls.size(),
                   allowedCalls.stream().map(ParallelToolCall::toolName)
                        .collect(Collectors.joining(", ")));

        // §7.13: Capture OTel context on the calling thread BEFORE submitting.
        // ContextAwareExecutorService propagates WorkspaceScoped only.
        // OTel ThreadLocal span context requires explicit propagation.
        final Context otelContext = Context.current();

        MDC.put(MdcKeys.PARALLEL_BATCH_SIZE, String.valueOf(allowedCalls.size()));
        MDC.put(MdcKeys.PARALLEL_TOOLS,
            allowedCalls.stream().map(ParallelToolCall::toolName)
                 .collect(Collectors.joining(",")));

        List<ParallelToolCall> allowedCompleted = new ArrayList<>();
        
        // §7.12: ExecutorService MUST be closed after batch completes.
        // WorkspaceExecutors.newVirtualThreadPerTaskExecutor() returns
        // ContextAwareExecutorService which implements AutoCloseable via
        // ExecutorService (Java 19+ close() calls shutdown() + awaitTermination).
        try (ExecutorService executor =
                WorkspaceExecutors.newVirtualThreadPerTaskExecutor()) {

            List<CompletableFuture<ParallelToolCall>> futures = allowedCalls.stream()
                .map(call -> CompletableFuture.supplyAsync(() -> {
                    // Restore OTel context in this virtual thread
                    try (Scope _ = otelContext.makeCurrent()) {
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

            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<ParallelToolCall> future = futures.get(i);
                try {
                    allowedCompleted.add(future.get(toolTimeoutSeconds, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return StepDecision.terminal(AgentResult.failed(
                        "Parallel tool execution interrupted.",
                        AgentResult.FailureCategory.SYSTEM_ERROR,
                        List.copyOf(history)));
                } catch (TimeoutException e) {
                    // §7.16: timeout — tool slot gets a FAIL result, batch continues
                    future.cancel(true); // attempt interrupt; virtual thread may not honour it
                    ParallelToolCall timedOut = allowedCalls.get(i);
                    log.warnf("[PARALLEL-TIMEOUT] session=%s tool=%s timed out after %ds",
                              context.sessionId(), timedOut.toolName(), toolTimeoutSeconds);
                    allowedCompleted.add(timedOut.withResult(
                        String.format("Tool timed out after %ds.", toolTimeoutSeconds),
                        false));
                } catch (ExecutionException e) {
                    log.errorf("[PARALLEL-EXEC] Unexpected future failure: %s",
                               e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    allowedCompleted.add(allowedCalls.get(i)
                        .withResult("Unexpected execution failure.", false));
                }
            }
        } finally {
            MDC.remove(MdcKeys.PARALLEL_BATCH_SIZE);
            MDC.remove(MdcKeys.PARALLEL_TOOLS);
        }

        // Pass 2: Execute only ALLOWED calls in parallel (virtual threads)
        // Merge: dedup results come first in original order for LLM clarity
        List<ParallelToolCall> allCompleted = new ArrayList<>();
        for (ParallelToolCall original : calls) {
            Optional<ParallelToolCall> dedup = dedupCalls.stream()
                .filter(d -> Objects.equals(d.callId(), original.callId()))
                .findFirst();
            if (dedup.isPresent()) {
                allCompleted.add(dedup.get());
            } else {
                allowedCompleted.stream()
                    .filter(c -> Objects.equals(c.callId(), original.callId()))
                    .findFirst()
                    .ifPresent(allCompleted::add);
            }
        }

        return buildCompletedBatchDecision(
            context, history, batchStep, allCompleted, consecutiveFailures);
    }

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

        String batchJson;
        try {
            batchJson = objectMapper.writeValueAsString(completed);
        } catch (Exception e) {
            log.errorf("[PARALLEL] Failed to serialize completed batch: %s", e.getMessage());
            return StepDecision.terminal(AgentResult.failed(
                "Failed to serialize parallel tool results.",
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(history)));
        }

        Map<String, Object> completedPayload = new HashMap<>();
        completedPayload.put("batch", batchJson);

        String summary = completed.stream()
            .map(c -> String.format("[%s → %s]", c.toolName(), c.success() ? "OK" : "FAIL"))
            .collect(Collectors.joining(", "));

        log.infof("[PARALLEL] session=%s batch completed: %s", context.sessionId(), summary);

        ReasoningStep completedBatchStep = new ReasoningStep(
            batchStep.thought(),
            ReasoningStep.PARALLEL_SENTINEL,
            completedPayload,
            summary,
            batchStep.inputTokens(),
            batchStep.outputTokens()
        );

        history.add(completedBatchStep);

        // §7.14 (corrected): ReasoningStep history is in-memory during execution.
        // AgentLoop.runWithRole() persists to SQLite via agentStore.save() at
        // session end or HITL pause. ChatMemoryWriter handles user/AI messages
        // only — it does NOT manage tool-step history. No write needed here.

        for (ParallelToolCall c : completed) {
            ReasoningStep toolStep = new ReasoningStep(
                null, c.toolName(), c.params(), c.observation(), 0, 0);
            observers.forEach(o -> o.onStepComplete(
                context.sessionId(), context.agentRole(), toolStep));
        }

        return StepDecision.proceed(completedBatchStep);
    }

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

        String compositeName = "__parallel__:" +
            calls.stream().map(ParallelToolCall::toolName)
                 .collect(Collectors.joining(","));

        // §7.20: Use persistBatch() to seed per-tool decision state.
        return StepDecision.terminal(
            checkpointManager.persistBatch(
                context, compositeName, batchJson, calls, history));
    }

    private List<ParallelToolCall> deserializeBatch(ReasoningStep batchStep, String sessionId) {
        try {
            Object batchRaw = batchStep.actionInput() != null
                ? batchStep.actionInput().get("batch") : null;
            if (batchRaw == null) return null;
            String batchJson = batchRaw instanceof String s ? s : objectMapper.writeValueAsString(batchRaw);
            return objectMapper.readValue(batchJson, BATCH_TYPE);
        } catch (Exception e) {
            log.errorf("[PARALLEL] session=%s: batch deserialization failed: %s", sessionId, e.getMessage());
            return null;
        }
    }
}
