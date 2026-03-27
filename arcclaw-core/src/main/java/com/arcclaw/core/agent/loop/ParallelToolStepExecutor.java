package com.arcclaw.core.agent.loop;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

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

        // 2. Validate all tools (deduplication + HITL check) — §7.7 BEFORE execution
        for (ParallelToolCall call : calls) {
            ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                context.sessionId(), context.agentRole(),
                call.toolName(), call.params(), history,
                context.goal(), List.of()); // skillSnapshot: pass empty array or inject SkillRegistry if needed

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
                   context.sessionId(), calls.size(),
                   calls.stream().map(ParallelToolCall::toolName)
                        .collect(Collectors.joining(", ")));

        ExecutorService executor = WorkspaceExecutors.newVirtualThreadPerTaskExecutor();
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
                log.errorf("[PARALLEL-EXEC] Unexpected future failure: %s",
                           e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                completed.add(calls.get(futures.indexOf(future))
                    .withResult("Unexpected execution failure.", false));
            }
        }

        // 5. Fold all results back into the batch step (§7.1, §7.2)
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

        // Changed implementation to match the new ApprovalCheckpointManager signature which returns AgentResult 
        // Need to wrap it into StepDecision.terminal
        return StepDecision.terminal(checkpointManager.persist(context, compositeName, batchJson, history));
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
