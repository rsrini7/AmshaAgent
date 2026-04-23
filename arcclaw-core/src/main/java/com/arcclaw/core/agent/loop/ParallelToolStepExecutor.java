package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.MdcCleaner;
import com.arcclaw.core.util.MdcKeys;
import com.arcclaw.core.util.PreExecutionFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
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
    @Inject MeterRegistry meterRegistry;

    @Inject
    Instance<ManagedExecutor> managedExecutors;

    private ManagedExecutor getInternalExecutor() {
        return managedExecutors.select(NamedLiteral.of("internal-tool-executor"))
                .stream().findFirst()
                .orElseGet(() -> managedExecutors.select(Default.Literal.INSTANCE).get());
    }

    private ManagedExecutor getExternalExecutor() {
        return managedExecutors.select(NamedLiteral.of("external-tool-executor"))
                .stream().findFirst()
                .orElseGet(() -> managedExecutors.select(Default.Literal.INSTANCE).get());
    }

    @ConfigProperty(name = "arcclaw.hitl.pre-executable-tools",
                    defaultValue = "")
    Optional<List<String>> preExecutableTools;

    @ConfigProperty(name = "arcclaw.tools.external-tools",
                    defaultValue = "playwright_navigate,playwright_click,playwright_type,playwright_press,playwright_select,playwright_scroll,playwright_screenshot,playwright_content,web_search")
    List<String> externalTools;


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
            if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval) {
                // §7.4: ANY tool needs HITL → pause ENTIRE batch atomically.
                // §7.30: PRE-EXECUTE tools whose autonomy level is >= FULL.
                log.infof("[PARALLEL-HITL] session=%s tool=%s requires approval " +
                          "— evaluating pre-execution for remaining %d tools.",
                          context.sessionId(), call.toolName(), calls.size());
                return handleHitlBatch(context, history, batchStep, calls);
            }

            if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked blocked) {
                log.warnf("[PARALLEL-BLOCKED] session=%s tool=%s blocked: %s",
                           context.sessionId(), call.toolName(), blocked.observation());
                String blockedObs = String.format(
                    "[PARALLEL-BATCH-BLOCKED] Tool '%s' was blocked: %s",
                    call.toolName(), blocked.observation());
                ReasoningStep failedStep = batchStep.withObservation(blockedObs, false);
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
        
        // §7.12: Executor pools are managed and persistent (§T5-2).
        try {

            List<CompletableFuture<ParallelToolCall>> futures = allowedCalls.stream()
                .map(call -> {
                    ManagedExecutor executor = isExternalTool(call.toolName()) ? getExternalExecutor() : getInternalExecutor();
                    return CompletableFuture.supplyAsync(() -> {
                        // Restore OTel context in this thread
                        try (Scope _ = otelContext.makeCurrent()) {
                            try {
                                return WorkspaceContext.run(
                                    context.sessionId(), context.userId(), context.chatId(),
                                    context.autonomyLevel(), context.agentRole(), context.channelType(),
                                    () -> {
                                        try {
                                            ToolResponse response = toolInvoker.invoke(
                                                 call.toolName(), call.params(), context.sessionId());
                                            if (response.success()) {
                                                guard.recordToolCall(context.sessionId(), call.toolName(), call.params(), response.output());
                                            }
                                            return call.withResult(response.output(), response.success());
                                        } catch (Exception e) {
                                            log.warnf("[PARALLEL-EXEC] session=%s tool=%s failed: %s",
                                                      context.sessionId(), call.toolName(), e.getMessage());
                                            return call.withResult(
                                                "Tool execution failed: " + e.getMessage(), false);
                                        }
                                    }
                                );
                            } catch (Exception e) {
                                log.errorf("[PARALLEL-EXEC] session=%s tool=%s context bind failed: %s",
                                          context.sessionId(), call.toolName(), e.getMessage());
                                return call.withResult("Internal context error: " + e.getMessage(), false);
                            }
                        }
                    }, executor);
                })
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
                    future.cancel(true);
                    ParallelToolCall timedOut = allowedCalls.get(i);
                    log.warnf("[PARALLEL-TIMEOUT] session=%s tool=%s timed out after %ds",
                              context.sessionId(), timedOut.toolName(), toolTimeoutSeconds);
                    meterRegistry.counter("arcclaw.agent.tool.timeout",
                            "tool", timedOut.toolName()).increment();
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
            MdcCleaner.clear(MdcKeys.PARALLEL_BATCH_SIZE, MdcKeys.PARALLEL_TOOLS);
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

        boolean allSuccess = completed.stream().allMatch(ParallelToolCall::success);

        ReasoningStep completedBatchStep = ReasoningStep.parallelBatch(
            batchStep.thought(),
            completedPayload,
            batchStep.inputTokens(),
            batchStep.outputTokens()
        ).withObservation(summary, allSuccess);

        history.add(completedBatchStep);

        // §7.14 (corrected): ReasoningStep history is in-memory during execution.
        // AgentLoop.runWithRole() persists to SQLite via agentStore.save() at
        // session end or HITL pause. ChatMemoryWriter handles user/AI messages
        // only — it does NOT manage tool-step history. No write needed here.

        for (ParallelToolCall c : completed) {
            ReasoningStep toolStep = ReasoningStep.pending(
                null, c.toolName(), c.params(), 0, 0)
                .withObservation(c.observation());
            observers.forEach(o -> o.onStepComplete(
                context.sessionId(), context.agentRole(), toolStep));
        }

        return StepDecision.proceed(completedBatchStep);
    }

    private StepDecision handleHitlBatch(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep batchStep,
            List<ParallelToolCall> calls) {

        String originalBatchJson;
        try {
            originalBatchJson = objectMapper.writeValueAsString(calls);
        } catch (Exception e) {
            return StepDecision.terminal(AgentResult.failed(
                "Failed to serialize batch for HITL approval.",
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(history)));
        }

        Set<String> safeTools = preExecutableTools
            .map(HashSet::new)
            .orElse(new HashSet<>());

        AutonomyLevel autonomy = context.autonomyLevel();

        PreExecutionFilter.SplitResult split =
            PreExecutionFilter.split(calls, autonomy, safeTools);

        String compositeName = ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX +
            calls.stream().map(ParallelToolCall::toolName)
                 .collect(Collectors.joining(","));

        // If nothing is pre-executable, persist normally §7.31
        if (split.preExecutable().isEmpty()) {
            List<ReasoningStep> historyAtPause = appendPendingBatchStep(history, batchStep);
            return StepDecision.terminal(
                checkpointManager.persistBatch(
                    context, compositeName, originalBatchJson, calls, historyAtPause,
                    context.channelType(), context.channelPlatformId()));
        }

        // Pre-execute the safe tools now (§7.30)
        Map<String, String> preResults = new LinkedHashMap<>();
        for (ParallelToolCall call : split.preExecutable()) {
            try {
                // Pre-execution MUST be under FULL autonomy regardless of session setting §7.30
                ToolResponse resp = WorkspaceContext.runWithFullAutonomy(
                    context.sessionId(), context.userId(), context.chatId(),
                    context.agentRole(),
                    () -> invokeTool(call, context.sessionId()));
                
                preResults.put(call.toolName(), objectMapper.writeValueAsString(resp));
                log.debugf("[PRE-EXEC] session=%s tool=%s succeeded.", context.sessionId(), call.toolName());
            } catch (Exception e) {
                log.warnf("[PRE-EXEC] session=%s tool %s failed pre-execution: %s",
                    context.sessionId(), call.toolName(), e.getMessage());
                ToolResponse errorResp = ToolResponse.failure(1, "Pre-execution failed: " + e.getMessage());
                try {
                    preResults.put(call.toolName(), objectMapper.writeValueAsString(errorResp));
                } catch (Exception ignored) {}
            }
        }

        // Rebuild compositeName with ONLY the HITL-pending tools §7.32 (UI constraint)
        // Note: originalBatchJson still contains ALL calls for context.
        String hitlCompositeName = ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX +
            split.hitlPending().stream()
            .map(ParallelToolCall::toolName)
            .collect(Collectors.joining(","));

        String preResultsJson;
        try {
            preResultsJson = objectMapper.writeValueAsString(preResults);
        } catch (Exception e) {
            log.errorf("Failed to serialize pre-executed results: %s", e.getMessage());
            List<ReasoningStep> historyAtPause = appendPendingBatchStep(history, batchStep);
            return StepDecision.terminal(checkpointManager.persistBatch(
                    context, compositeName, originalBatchJson, calls, historyAtPause,
                    context.channelType(), context.channelPlatformId()));
        }

        List<ReasoningStep> historyAtPause = appendPendingBatchStep(history, batchStep);
        return StepDecision.terminal(
            checkpointManager.persistBatchWithPreExecuted(
                context, hitlCompositeName, originalBatchJson,
                split.hitlPending(), preResultsJson, historyAtPause,
                context.channelType(), context.channelPlatformId()));
    }

    private ToolResponse invokeTool(ParallelToolCall call, String sessionId) {
        return toolInvoker.invoke(call.toolName(), call.params(), sessionId);
    }

    private boolean isExternalTool(String toolName) {
        if (toolName == null) return false;
        // Strip provider prefix if present (e.g. "github:read" -> "read")
        String baseName = toolName.contains(":") ? toolName.split(":")[1] : toolName;
        return externalTools.contains(toolName) || externalTools.contains(baseName);
    }

    private List<ReasoningStep> appendPendingBatchStep(List<ReasoningStep> history, ReasoningStep batchStep) {
        List<ReasoningStep> snapshot = new ArrayList<>(history);
        snapshot.add(batchStep);
        return List.copyOf(snapshot);
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
