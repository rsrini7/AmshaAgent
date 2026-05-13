package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final ObjectMapper objectMapper;
    private final ToolInvocationGuard guard;
    private final ApprovalCheckpointManager checkpointManager;
    private final Instance<AgentObserver> observers;
    private final GovernanceStrategy governanceLayer;
    private final RateLimitTracker rateLimitTracker;
    private final MeterRegistry meterRegistry;
    private final SequentialToolExecutor sequentialToolExecutor;
    private final Instance<ManagedExecutor> managedExecutors;
    private final Optional<List<String>> preExecutableTools;
    private final List<String> externalTools;
    private final long toolTimeoutSeconds;
    private final BatchResultAggregator batchResultAggregator;

    @Inject
    public ParallelToolStepExecutor(
            ObjectMapper objectMapper,
            ToolInvocationGuard guard,
            ApprovalCheckpointManager checkpointManager,
            Instance<AgentObserver> observers,
            GovernanceStrategy governanceLayer,
            RateLimitTracker rateLimitTracker,
            MeterRegistry meterRegistry,
            SequentialToolExecutor sequentialToolExecutor,
            Instance<ManagedExecutor> managedExecutors,
            @ConfigProperty(name = "arcclaw.hitl.pre-executable-tools", defaultValue = "") Optional<List<String>> preExecutableTools,
            @ConfigProperty(name = "arcclaw.tools.external-tools", defaultValue = "browser_navigate,web_search") List<String> externalTools,
            @ConfigProperty(name = "arcclaw.parallel.tool-timeout-seconds", defaultValue = "60") long toolTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.guard = guard;
        this.checkpointManager = checkpointManager;
        this.observers = observers;
        this.governanceLayer = governanceLayer;
        this.rateLimitTracker = rateLimitTracker;
        this.meterRegistry = meterRegistry;
        this.sequentialToolExecutor = sequentialToolExecutor;
        this.managedExecutors = managedExecutors;
        this.preExecutableTools = preExecutableTools;
        this.externalTools = externalTools;
        this.toolTimeoutSeconds = toolTimeoutSeconds;
        this.batchResultAggregator = new BatchResultAggregator(objectMapper, this.observers);
    }

    protected ParallelToolStepExecutor() {
        this(null, null, null, null, null, null, null, null, null, Optional.empty(), List.of(), 60);
    }

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

    private static final TypeReference<List<ParallelToolCall>> BATCH_TYPE = new TypeReference<>() {};

    /**
     * Entry point. Called by ToolStepHandler when step.isParallelBatch() == true.
     */
    public StepDecision execute(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep batchStep,
            int consecutiveFailures) {

        List<ParallelToolCall> calls = deserializeBatch(batchStep, context.sessionId());
        if (calls == null || calls.isEmpty()) {
            log.errorf("[PARALLEL] session=%s: empty or corrupt batch - terminating.", context.sessionId());
            return StepDecision.terminal(AgentResult.failed(
                "Parallel tool batch was empty or corrupt.",
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(history)));
        }

        log.infof("[PARALLEL] session=%s: executing %d tool calls: %s",
                  context.sessionId(), calls.size(),
                  calls.stream().map(ParallelToolCall::toolName)
                       .collect(Collectors.joining(", ")));

        GuardEvaluationResult guardRes = evaluateGuardForCalls(context, history, batchStep, calls, consecutiveFailures);
        if (guardRes.hitlOrBlockedDecision != null) {
            return guardRes.hitlOrBlockedDecision;
        }

        List<ParallelToolCall> allowedCalls = guardRes.allowedCalls;
        List<ParallelToolCall> dedupCalls = guardRes.dedupCalls;

        if (allowedCalls.isEmpty()) {
            log.infof("[PARALLEL-DEDUP] session=%s all %d tools were duplicates " +
                      "- returning merged dedup batch.",
                      context.sessionId(), calls.size());
            List<ParallelToolCall> allDedup = new ArrayList<>(dedupCalls);
            return batchResultAggregator.buildCompletedBatchDecision(
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
                      "is in rate-limit backoff. Parallel tool batch of %d will proceed.",
                      context.sessionId(), calls.size());
        }

        final Context otelContext = Context.current();

        MDC.put(MdcKeys.PARALLEL_BATCH_SIZE, String.valueOf(allowedCalls.size()));
        MDC.put(MdcKeys.PARALLEL_TOOLS,
            allowedCalls.stream().map(ParallelToolCall::toolName)
                 .collect(Collectors.joining(",")));

        List<ParallelToolCall> allowedCompleted;
        try {
            allowedCompleted = runAllowedCallsAsync(context, history, allowedCalls, otelContext);
        } catch (RuntimeException re) {
            if ("Interrupted".equals(re.getMessage())) {
                return StepDecision.terminal(AgentResult.failed(
                    "Parallel tool execution interrupted.",
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(history)));
            }
            throw re;
        } finally {
            MdcCleaner.clear(MdcKeys.PARALLEL_BATCH_SIZE, MdcKeys.PARALLEL_TOOLS);
        }

        List<ParallelToolCall> allCompleted = batchResultAggregator.mergeCompletedCalls(calls, dedupCalls, allowedCompleted);

        return batchResultAggregator.buildCompletedBatchDecision(
            context, history, batchStep, allCompleted, consecutiveFailures);
    }

    private static class GuardEvaluationResult {
        StepDecision hitlOrBlockedDecision;
        List<ParallelToolCall> allowedCalls = new ArrayList<>();
        List<ParallelToolCall> dedupCalls = new ArrayList<>();
    }

    private GuardEvaluationResult evaluateGuardForCalls(
            AgentExecutionContext context, List<ReasoningStep> history, ReasoningStep batchStep,
            List<ParallelToolCall> calls, int consecutiveFailures) {

        GuardEvaluationResult r = new GuardEvaluationResult();
        for (ParallelToolCall call : calls) {
            ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                context.sessionId(), context.agentRole(), context.autonomyLevel(),
                call.toolName(), call.params(), history,
                context.goal(), List.of());
            if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval) {
                log.infof("[PARALLEL-HITL] session=%s tool=%s requires approval " +
                          "- evaluating pre-execution for remaining %d tools.",
                          context.sessionId(), call.toolName(), calls.size());
                r.hitlOrBlockedDecision = handleHitlBatch(context, history, batchStep, calls);
                return r;
            }

            if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked blocked) {
                log.warnf("[PARALLEL-BLOCKED] session=%s tool=%s blocked: %s",
                           context.sessionId(), call.toolName(), blocked.observation());
                String blockedObs = String.format(
                    "[PARALLEL-BATCH-BLOCKED] Tool '%s' was blocked: %s",
                    call.toolName(), blocked.observation());
                ReasoningStep failedStep = batchStep.withObservation(blockedObs, false);
                history.add(failedStep);
                observers.forEach(o -> o.onStepComplete(
                    context.sessionId(), context.agentRole(), failedStep));
                r.hitlOrBlockedDecision = StepDecision.retryValidation(consecutiveFailures + 1);
                return r;
            }

            if (decision instanceof ToolInvocationGuard.GuardDecision.Deduplicate dedup) {
                log.infof("[PARALLEL-DEDUP] session=%s tool=%s is duplicate - " +
                          "using cached observation, continuing with remaining %d tools.",
                          context.sessionId(), call.toolName(),
                          calls.size() - 1);
                r.dedupCalls.add(call.withResult("[DEDUP] " + dedup.cachedObservation(), true));
                continue; 
            }

            r.allowedCalls.add(call);
        }
        return r;
    }

    private List<ParallelToolCall> runAllowedCallsAsync(
            AgentExecutionContext context, List<ReasoningStep> history, List<ParallelToolCall> allowedCalls, Context otelContext) {

        List<ParallelToolCall> allowedCompleted = new ArrayList<>();
        List<CompletableFuture<ParallelToolCall>> futures = allowedCalls.stream()
            .map(call -> {
                ManagedExecutor executor = isExternalTool(call.toolName()) ? getExternalExecutor() : getInternalExecutor();
                return CompletableFuture.supplyAsync(() -> 
                    sequentialToolExecutor.execute(context, call, otelContext), 
                    executor);
            })
            .toList();

        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<ParallelToolCall> future = futures.get(i);
            try {
                allowedCompleted.add(future.get(toolTimeoutSeconds, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted");
            } catch (TimeoutException e) {
                future.cancel(true);
                ParallelToolCall timedOut = allowedCalls.get(i);
                log.warnf("[PARALLEL-TIMEOUT] session=%s tool=%s timed out after %ds",
                          context.sessionId(), timedOut.toolName(), toolTimeoutSeconds);
                meterRegistry.counter("arcclaw.agent.tool.timeout",
                        "tool", timedOut.toolName()).increment();
                allowedCompleted.add(timedOut.withResult(
                    String.format("Tool timed out after %ds.", toolTimeoutSeconds),
                    false));
                observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(),
                    ReasoningStep.observe(timedOut.toolName(),
                        String.format("Tool timed out after %ds.", toolTimeoutSeconds),
                        "System")));
            } catch (ExecutionException e) {
                log.errorf("[PARALLEL-EXEC] Unexpected future failure: %s",
                           e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                ParallelToolCall failedCall = allowedCalls.get(i);
                allowedCompleted.add(failedCall.withResult("Unexpected execution failure.", false));
                observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(),
                    ReasoningStep.observe(failedCall.toolName(),
                        "Unexpected execution failure.", "System")));
            }
        }
        return allowedCompleted;
    }

    private StepDecision handleHitlBatch(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep batchStep,
            List<ParallelToolCall> calls) {

        Set<String> safeTools = preExecutableTools
            .map(HashSet::new)
            .orElse(new HashSet<>());

        AutonomyLevel autonomy = context.autonomyLevel();

        PreExecutionFilter.SplitResult split =
            PreExecutionFilter.split(calls, autonomy, safeTools);

        if (split.preExecutable().isEmpty()) {
            List<ReasoningStep> historyAtPause = batchResultAggregator.appendPendingBatchStep(history, batchStep);
            return StepDecision.terminal(
                checkpointManager.persistBatch(
                    context, calls, historyAtPause,
                    context.channelType(), context.channelPlatformId()));
        }

        Map<String, ToolResponse> preResults = new LinkedHashMap<>();
        for (ParallelToolCall call : split.preExecutable()) {
            try {
                ToolResponse resp = WorkspaceContext.runWithFullAutonomy(
                    context.sessionId(), context.userId(), context.chatId(),
                    context.agentRole(),
                    () -> sequentialToolExecutor.invoke(call.toolName(), call.params(), context.sessionId()));
                
                preResults.put(call.toolName(), resp);
                log.debugf("[PRE-EXEC] session=%s tool=%s succeeded.", context.sessionId(), call.toolName());
            } catch (Exception e) {
                log.warnf("[PRE-EXEC] session=%s tool %s failed pre-execution: %s",
                    context.sessionId(), call.toolName(), e.getMessage());
                preResults.put(call.toolName(), ToolResponse.failure(1, "Pre-execution failed: " + e.getMessage()));
            }
        }

        List<ReasoningStep> historyAtPause = batchResultAggregator.appendPendingBatchStep(history, batchStep);
        return StepDecision.terminal(
            checkpointManager.persistBatchWithPreExecuted(
                context, calls, preResults, historyAtPause,
                context.channelType(), context.channelPlatformId()));
    }

    private boolean isExternalTool(String toolName) {
        if (toolName == null) return false;
        String baseName = toolName.contains(":") ? toolName.split(":")[1] : toolName;
        return externalTools.contains(toolName) || externalTools.contains(baseName);
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
