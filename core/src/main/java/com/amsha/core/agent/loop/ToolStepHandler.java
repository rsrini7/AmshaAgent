package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.RequiresApprovalException;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Handles tool name validation, 4-phase tool invocation,
 * and the consecutive-failure circuit breaker.
 *
 * <p>FIX §1: TOOL-GUARD silently rewrites coordinator github calls to
 * garuda instead of returning a retryEngine hint.
 * This collapses 2–3 wasted iterations (OpenRouter ignores hints) to 1.
 *
 * <p>FIX §2: garuda rewrite params use key "agent" (not "role").
 * DelegateToAgentTool.execute() calls params.get("agent") - using "role"
 * caused "Parameter 'agent' is required." to be sent to the user on every
 * cron HITL resume.
 *
 * <p>Refactored to constructor injection as part of T4-1.
 */
@ApplicationScoped
public class ToolStepHandler {

    private static final Logger log = Logger.getLogger(ToolStepHandler.class);

    private final Instance<AgentObserver> observers;
    private final ToolInvocationGuard guard;
    private final ToolInvoker invoker;
    private final ApprovalCheckpointManager checkpointManager;
    private final ParallelToolStepExecutor parallelExecutor;
    private final MeterRegistry meterRegistry;
    private final ToolResultProcessor resultProcessor;
    private final ExecutionFailureRecoveryService recoveryService;
    private final ToolStepValidator validator;
    private final ToolStepEscalationHandler escalationHandler;

    @Inject
    public ToolStepHandler(
            Instance<AgentObserver> observers,
            ToolInvocationGuard guard,
            ToolInvoker invoker,
            ChatMemoryWriter writer,
            ApprovalCheckpointManager checkpointManager,
            ParallelToolStepExecutor parallelExecutor,
            MeterRegistry meterRegistry,
            ExecutionFailureRecoveryService recoveryService,
            ToolStepValidator validator,
            ToolStepEscalationHandler escalationHandler) {
        this.observers = observers;
        this.guard = guard;
        this.invoker = invoker;
        this.checkpointManager = checkpointManager;
        this.parallelExecutor = parallelExecutor;
        this.meterRegistry = meterRegistry;
        this.resultProcessor = new ToolResultProcessor(writer);
        this.recoveryService = recoveryService;
        this.validator = validator;
        this.escalationHandler = escalationHandler;
    }

    // ── Validation

    StepDecision validateTool(
            AgentExecutionContext context, List<ReasoningStep> history, ReasoningStep step, int consecutiveFailures) {

        if (step.isParallelBatch()) {
            return StepDecision.proceed(step);
        }

        String toolName = step.action();

        if (ToolStepResolutionUtils.isHallucinatedFinish(step)) {
            return StepDecision.proceed(ToolStepResolutionUtils.rescueFinish(step, context.sessionId()));
        }

        StepDecision decision = validator.validate(context, history, step, toolName, consecutiveFailures);
        if (decision != null) {
            return decision;
        }

        return StepDecision.proceedWithFailures(step, consecutiveFailures);
    }

    // ── Invocation

    /** Orchestrates the 4 phases: guard → invoke → observe → resolve outcome. */
    StepDecision invokeToolAndObserve(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep step,
            int consecutiveFailures,
            List<CompiledSkillEntry> skillSnapshot) {

        // Parallel batch - delegate to ParallelToolStepExecutor
        if (step.isParallelBatch()) {
            return parallelExecutor.execute(context, history, step, consecutiveFailures);
        }

        // Phase 1 - Guard
        StepDecision guardDecision = evaluateGuard(context, history, step, skillSnapshot);
        if (guardDecision != null) return guardDecision;

        // Phase 2 - Invoke
        String toolName = step.action();
        Map<String, Object> params = step.actionInput() != null ? step.actionInput() : Map.of();
        ToolResponse response;
        InvokeResult invokeResult = invokeTool(context, history, step, toolName, params);
        if (invokeResult instanceof InvokeResult.ShortCircuit sc) {
            return sc.decision();
        }
        response = ((InvokeResult.Responded) invokeResult).response();

        if (response instanceof ToolResponse.ToolFailure failure && failure.isInfraFailure()) {
            log.errorf(
                    "[TOOL-STEP] Infrastructure failure for tool '%s' session=%s: %s",
                    toolName, context.sessionId(), failure.output());
            history.add(step.withObservation("INFRA_FAILURE: " + failure.output()));
            return StepDecision.terminal(AgentResult.failed(
                    "⚠️ " + failure.output(), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }

        // Track whether auto-heal already attempted escalation to prevent duplicate
        boolean autoHealAttemptedEscalation = false;

        // Auto-heal hook: intercept sandbox_exec failures for coder and delegate to Shiva
        if (response instanceof ToolResponse.ToolFailure failure
                && recoveryService.shouldAttemptRecovery(context.agentRole(), toolName, response)) {
            log.infof(
                    "[AutoHeal] Detected recoverable sandbox_exec failure for session %s, invoking Shiva debugger",
                    context.sessionId());
            String originalCode = extractParam(params, "code");
            String language = extractParam(params, "language");
            if (language == null || language.isBlank()) {
                log.warnf(
                        "[AutoHeal] Skipping recovery for session %s because sandbox_exec language is missing",
                        context.sessionId());
                autoHealAttemptedEscalation = true;
            } else {
                String originalGoal = context.goal();

                ExecutionFailureRecoveryService.RecoveryResult recovery = recoveryService.attemptRecovery(
                        context.sessionId(),
                        context.userId(),
                        context.chatId(),
                        originalCode,
                        language,
                        failure.output(),
                        context.channelType(),
                        context.channelPlatformId(),
                        originalGoal);

                if (recovery.success()) {
                    // Pre-re-execution validation: skip podman if code is obviously broken
                    String execValidationError = recoveryService.validateForExecution(recovery.fixedCode(), language);
                    if (execValidationError != null) {
                        log.warnf("[AutoHeal] Fixed code failed pre-execution validation: %s", execValidationError);
                        response = escalationHandler.attemptEscalationAfterHealFailure(context, params, failure);
                    } else {
                        log.infof(
                                "[AutoHeal] Recovery succeeded, re-executing sandbox_exec with fixed code (attempts=%d)",
                                recovery.attemptsUsed());
                        Map<String, Object> fixedParams = new HashMap<>(params);
                        fixedParams.put("code", recovery.fixedCode());
                        ToolResponse retriedResponse =
                                invokeToolWithParams(context, history, step, toolName, fixedParams);

                        if (retriedResponse instanceof ToolResponse.ToolFailure retryFailure
                                && !retryFailure.isInfraFailure()) {
                            log.warnf("[AutoHeal] Re-execution also failed, attempting persona escalation");
                            response =
                                    escalationHandler.attemptEscalationAfterHealFailure(context, params, retryFailure);
                        } else {
                            response = retriedResponse;
                        }
                    }
                    autoHealAttemptedEscalation = true;
                } else {
                    autoHealAttemptedEscalation = true;
                    log.warnf(
                            "[AutoHeal] Recovery failed: %s, attempting persona escalation", recovery.errorIfFailed());
                    response = escalationHandler.attemptEscalationAfterHealFailure(context, params, failure);
                }
            }
        }

        // Skip generic escalation hook if auto-heal already attempted it
        if (!autoHealAttemptedEscalation && response instanceof ToolResponse.ToolFailure failure) {
            ToolResponse escalated = escalationHandler.attemptGenericEscalation(context, toolName, params, failure);
            if (escalated != null) {
                response = escalated;
            }
        }

        // Phase 3 - Observe
        String observation = resultProcessor.buildObservation(response);
        final ReasoningStep completedStep =
                step.withObservation(observation, response instanceof ToolResponse.ToolSuccess);
        history.add(completedStep);

        // Record for future O(1) deduplication in this turn
        if (response instanceof ToolResponse.ToolSuccess) {
            guard.recordToolCall(context.sessionId(), toolName, params, observation);
        }

        observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), completedStep));
        log.debugf(
                "Observation for '%s': %s",
                toolName, observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

        // Phase 4 - Resolve
        return resultProcessor.resolveOutcome(
                context, toolName, params, response, observation, completedStep, history, consecutiveFailures);
    }

    private StepDecision evaluateGuard(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep step,
            List<CompiledSkillEntry> skillSnapshot) {

        if (context.autonomyLevel() == AutonomyLevel.READONLY) {
            log.infof(
                    "[ToolStepHandler] READONLY session %s - blocking tool '%s' inline",
                    context.sessionId(), step.action());
            final ReasoningStep blockedStep = step.withObservation(
                    "Tool execution is not permitted in READONLY mode. " + "Answer using only your existing knowledge. "
                            + "Do not attempt to call any tools.",
                    false);
            history.add(blockedStep);
            observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), blockedStep));
            return StepDecision.continueLoop(null, 0);
            // → skips LLM; re-enters inner validation loop immediately (isRetryValidation=true)
        }

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                context.sessionId(),
                context.agentRole(),
                context.autonomyLevel(),
                step.action(),
                step.actionInput() != null ? step.actionInput() : Map.of(),
                history,
                context.goal(),
                skillSnapshot);

        if (decision instanceof ToolInvocationGuard.GuardDecision.Deduplicate d) {
            final ReasoningStep dedupStep = step.withObservation(d.cachedObservation(), true);
            history.add(dedupStep);
            observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), dedupStep));
            return StepDecision.proceedWithFailures(dedupStep, 0);
        }

        if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked b) {
            final ReasoningStep blockedStep = step.withObservation(b.observation(), false);
            history.add(blockedStep);
            observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), blockedStep));

            // A deterministic validation/policy rejection must not cause the
            // state machine to spend the entire iteration budget asking the
            // model to repeat the same invalid call.  Keep the valid history,
            // but terminate this turn after the same blocked invocation is
            // observed twice so the caller can correct the parameters and
            // resume the session.
            long repeatedBlockedCalls = history.stream()
                    .filter(s -> s instanceof ReasoningStep.ToolCallStep)
                    .filter(s -> Objects.equals(s.action(), step.action()))
                    .filter(s -> Objects.equals(s.actionInput(), step.actionInput()))
                    .filter(s -> Boolean.FALSE.equals(s.success()))
                    .count();
            log.warnf(
                    "[TOOL-GUARD] Blocked tool '%s' for session %s (repeat=%d): %s",
                    step.action(), context.sessionId(), repeatedBlockedCalls, b.observation());
            if (repeatedBlockedCalls >= 2) {
                return StepDecision.terminal(AgentResult.failed(
                        "Tool call blocked repeatedly: " + b.observation(),
                        AgentResult.FailureCategory.SCHEMA_ERROR,
                        List.copyOf(history)));
            }

            if (context.sessionId() != null
                    && context.sessionId().contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)
                    && ToolCapability.GITHUB.toolName().equals(step.action())) {
                log.infof(
                        "[TOOL-GUARD] Inner session %s: github blocked " + "- fast-pathing to FINISH",
                        context.sessionId());
                return StepDecision.terminal(AgentResult.finished(b.observation(), List.copyOf(history)));
            }
            return StepDecision.continueLoop(null, 0);
            // → skips LLM; re-enters inner validation loop immediately (isRetryValidation=true)
        }

        if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval p) {
            List<ReasoningStep> historyAtPause = appendPendingStep(history, step);
            return StepDecision.terminal(checkpointManager.persist(
                    context, p.frame(), historyAtPause, context.channelType(), context.channelPlatformId()));
        }
        return null; // PROCEED
    }

    private InvokeResult invokeTool(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep step,
            String toolName,
            Map<String, Object> params) {

        var timer = Timer.builder("amsha.tool.invocation")
                .description("Duration of tool execution")
                .tag("tool", toolName)
                .tag("role", context.agentRole())
                .publishPercentiles(0.5, 0.95);

        var sample = Timer.start(meterRegistry);
        String exceptionClass = "none";

        try {
            final String suggestedCron = (context != null && context.data() != null)
                    ? String.valueOf(context.data().get("SUGGESTED_CRON"))
                    : null;
            final String suggestedNextFireTime = (context != null && context.data() != null)
                    ? String.valueOf(context.data().get("SUGGESTED_NEXT_FIRE_TIME"))
                    : null;
            final String suggestedCronSource = (context != null && context.data() != null)
                    ? String.valueOf(context.data().get("SUGGESTED_CRON_SOURCE"))
                    : null;

            return WorkspaceContext.run(
                    context.sessionId(),
                    context.userId(),
                    context.chatId(),
                    context.autonomyLevel(),
                    context.agentRole(),
                    context.channelType(),
                    () -> {

                        // Use java.lang.ScopedValue.where directly for extra data since WorkspaceContext helper
                        // only supports basic 6 context values.
                        return ScopedValue.where(WorkspaceContext.SUGGESTED_CRON, suggestedCron)
                                .where(WorkspaceContext.SUGGESTED_NEXT_FIRE_TIME, suggestedNextFireTime)
                                .where(WorkspaceContext.SUGGESTED_CRON_SOURCE, suggestedCronSource)
                                .call(() -> new InvokeResult.Responded(
                                        invoker.invoke(toolName, params, context.sessionId())));
                    });
        } catch (RequiresApprovalException e) {
            exceptionClass = e.getClass().getSimpleName();
            List<ReasoningStep> historyAtPause = appendPendingStep(history, step);
            return new InvokeResult.ShortCircuit(StepDecision.terminal(checkpointManager.persist(
                    context, e.getFrame(), historyAtPause, context.channelType(), context.channelPlatformId())));
        } catch (Exception e) {
            exceptionClass = e.getClass().getSimpleName();
            log.errorf("Tool invocation failed for '%s': %s", toolName, LogUtil.rootMessage(e));
            return new InvokeResult.Responded(ToolResponse.failure(1, e.getMessage()));
        } finally {
            sample.stop(timer.tag("exception", exceptionClass).register(meterRegistry));
        }
    }

    private String extractParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : (val != null ? val.toString() : "");
    }

    private ToolResponse invokeToolWithParams(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep step,
            String toolName,
            Map<String, Object> params) {
        InvokeResult result = invokeTool(context, history, step, toolName, params);
        if (result instanceof InvokeResult.ShortCircuit sc) {
            return ToolResponse.failure(1, "Auto-heal re-execution blocked by guard: " + sc.decision());
        }
        return ((InvokeResult.Responded) result).response();
    }

    private List<ReasoningStep> appendPendingStep(List<ReasoningStep> history, ReasoningStep step) {
        List<ReasoningStep> snapshot = new ArrayList<>(history);
        snapshot.add(step);
        return List.copyOf(snapshot);
    }

    /** Sealed result of the invokeTool phase. */
    protected sealed interface InvokeResult {
        record Responded(ToolResponse response) implements InvokeResult {}

        record ShortCircuit(StepDecision decision) implements InvokeResult {}
    }
}
