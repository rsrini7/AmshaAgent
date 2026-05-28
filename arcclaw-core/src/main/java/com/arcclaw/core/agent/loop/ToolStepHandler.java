package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.tool.DynamicToolLookup;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;

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

    private static final Set<String> REACT_KEYWORDS = Set.of("OBSERVE", "THINK", "ACT", "PLAN", "REASON", "THOUGHT");

    private static final Pattern GITHUB_RESOURCE_PATTERN = Pattern.compile(
            "(?i)(github\\.com|[a-zA-Z0-9_.-]{2,}/[a-zA-Z0-9_.-]{2,}(?!\\/)" + "|gh\\s+repo|gist\\.github\\.com)");

    private final ToolRegistry toolRegistry;
    private final Instance<AgentObserver> observers;
    private final ToolInvocationGuard guard;
    private final ToolInvoker invoker;
    private final Instance<DynamicToolLookup> dynamicToolLookup;
    private final ApprovalCheckpointManager checkpointManager;
    private final ParallelToolStepExecutor parallelExecutor;
    private final MeterRegistry meterRegistry;
    private final ToolResultProcessor resultProcessor;
    private final ExecutionFailureRecoveryService recoveryService;
    private final PersonaEscalationService escalationService;

    @Inject
    public ToolStepHandler(
            ToolRegistry toolRegistry,
            Instance<AgentObserver> observers,
            ToolInvocationGuard guard,
            ToolInvoker invoker,
            ChatMemoryWriter writer,
            Instance<DynamicToolLookup> dynamicToolLookup,
            ApprovalCheckpointManager checkpointManager,
            ParallelToolStepExecutor parallelExecutor,
            MeterRegistry meterRegistry,
            ExecutionFailureRecoveryService recoveryService,
            PersonaEscalationService escalationService) {
        this.toolRegistry = toolRegistry;
        this.observers = observers;
        this.guard = guard;
        this.invoker = invoker;
        this.dynamicToolLookup = dynamicToolLookup;
        this.checkpointManager = checkpointManager;
        this.parallelExecutor = parallelExecutor;
        this.meterRegistry = meterRegistry;
        this.resultProcessor = new ToolResultProcessor(writer);
        this.recoveryService = recoveryService;
        this.escalationService = escalationService;
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

        // 2. Coordinator-Github Redirect (§1 & §2)
        if (isCoordinatorGithubViolation(context, toolName, step)) {
            return StepDecision.proceedWithFailures(
                    redirectToDelegate(step, context.sessionId(), "researcher"), consecutiveFailures);
        }

        // 🟢 FIX: Specialist Tool Redirection
        // If coordinator or researcher attempts a specialist tool (sandbox, search, browser) for which it
        // lacks capabilities, redirect to the appropriate specialist agent via garuda.
        // Guard: don't redirect if this step is already a delegation to avoid infinite loops.
        String currentRole = context.agentRole();
        if ((AgentRoleEnum.COORDINATOR.toScopedName().equals(currentRole)
                        || AgentRoleEnum.RESEARCHER.toScopedName().equals(currentRole))
                && !ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)) {

            if (ToolCapability.SANDBOX_EXEC.toolName().equals(toolName)) {
                return StepDecision.proceedWithFailures(
                        redirectToDelegate(step, context.sessionId(), "coder"), consecutiveFailures);
            }

            // Coordinator lacks research tools, but researcher HAS them - only redirect coordinator for these
            if (AgentRoleEnum.COORDINATOR.toScopedName().equals(currentRole)) {
                if (ToolCapability.WEB_SEARCH_BRAVE.toolName().equals(toolName)
                        || ToolCapability.WEB_SEARCH_SERPER.toolName().equals(toolName)
                        || ToolCapability.BROWSER_NAVIGATE.toolName().equals(toolName)) {
                    return StepDecision.proceedWithFailures(
                            redirectToDelegate(step, context.sessionId(), "researcher"), consecutiveFailures);
                }
            }
        }

        // 3. Known or Dynamic Tool check
        if (ToolStepResolutionUtils.isKnownTool(toolName, toolRegistry, dynamicToolLookup)) {
            return StepDecision.proceedWithFailures(step, consecutiveFailures);
        }

        // 4. ReAct keyword or Hallucination
        return handleUnknownTool(context, history, step, toolName, consecutiveFailures);
    }

    private boolean isCoordinatorGithubViolation(AgentExecutionContext context, String toolName, ReasoningStep step) {
        if (!AgentRoleEnum.COORDINATOR.toScopedName().equals(context.agentRole())
                || !ToolCapability.GITHUB.toolName().equals(toolName)) {
            return false;
        }
        boolean goalHasRes = context.goal() != null
                && GITHUB_RESOURCE_PATTERN.matcher(context.goal()).find();
        boolean argsHaveRes = step.actionInput() != null
                && (step.actionInput().values().stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .anyMatch(
                                        v -> GITHUB_RESOURCE_PATTERN.matcher(v).find())
                        || (step.actionInput().get("target") instanceof String t && !t.isBlank()));
        return !goalHasRes && !argsHaveRes;
    }

    private ReasoningStep redirectToDelegate(ReasoningStep step, String sessionId, String agent) {
        log.warnf(
                "[TOOL-GUARD] Coordinator %s auto-redirect for session %s to agent %s",
                step.action(), sessionId, agent);
        String delegateGoal = ToolStepResolutionUtils.buildDelegateGoal(
                step.action(), step.actionInput() != null ? step.actionInput() : Map.of());
        return ReasoningStep.pending(
                step.thought(),
                ToolCapability.DELEGATE_TO_AGENT.toolName(),
                Map.of("agent", agent, "goal", delegateGoal),
                0,
                0);
    }

    private StepDecision handleUnknownTool(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep step,
            String toolName,
            int consecutiveFailures) {
        if (REACT_KEYWORDS.contains(toolName.toUpperCase(Locale.ROOT))) {
            return StepDecision.continueLoop(
                    String.format(
                            "System: '%s' is a ReAct reasoning keyword. Available tools: %s.",
                            toolName, toolRegistry.listTools()),
                    0);
        }

        String errorObs =
                String.format("Tool '%s' does not exist. Available tools: %s.", toolName, toolRegistry.listTools());
        ReasoningStep completedStep = step.withObservation(errorObs, false);
        history.add(completedStep);
        observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), completedStep));

        int failures = consecutiveFailures + 1;
        if (failures >= 2) {
            return StepDecision.terminal(AgentResult.finished(
                    "❌ I encountered repeated errors. Please rephrase your request.", List.copyOf(history)));
        }
        return StepDecision.continueLoop(null, 0);

        // → skips LLM; re-enters inner validation loop immediately (isRetryValidation=true)
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

        // Auto-heal hook: intercept sandbox_exec failures for coder and delegate to Shiva
        if (response instanceof ToolResponse.ToolFailure failure
                && recoveryService.shouldAttemptRecovery(context.agentRole(), toolName, response)) {
            log.infof(
                    "[AutoHeal] Detected recoverable sandbox_exec failure for session %s, invoking Shiva debugger",
                    context.sessionId());
            String originalCode = extractParam(params, "code");
            String language = extractParam(params, "language");
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
                log.infof(
                        "[AutoHeal] Recovery succeeded, re-executing sandbox_exec with fixed code (attempts=%d)",
                        recovery.attemptsUsed());
                Map<String, Object> fixedParams = new HashMap<>(params);
                fixedParams.put("code", recovery.fixedCode());
                ToolResponse retriedResponse = invokeToolWithParams(context, history, step, toolName, fixedParams);

                if (retriedResponse instanceof ToolResponse.ToolFailure retryFailure
                        && !retryFailure.isInfraFailure()) {
                    log.warnf("[AutoHeal] Re-execution also failed, attempting persona escalation");
                    response = attemptEscalationAfterHealFailure(
                            context, step, toolName, params, failure, retryFailure, history);
                } else {
                    response = retriedResponse;
                }
            } else {
                log.warnf("[AutoHeal] Recovery failed: %s, attempting persona escalation", recovery.errorIfFailed());
                response =
                        attemptEscalationAfterHealFailure(context, step, toolName, params, failure, failure, history);
            }
        }

        // Persona escalation hook: intercept failures that match escalation patterns
        if (response instanceof ToolResponse.ToolFailure failure
                && escalationService.shouldAttemptEscalation(context.agentRole(), toolName, response)) {
            String targetRole =
                    escalationService.determineEscalationTarget(context.agentRole(), failure.output(), context.goal());
            if (targetRole != null) {
                log.infof(
                        "[PersonaEscalation] Detected escalation pattern for session %s, routing %s → %s",
                        context.sessionId(), context.agentRole(), targetRole);
                response = attemptEscalation(context, step, toolName, params, failure, targetRole, history);
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

        var timer = Timer.builder("arcclaw.tool.invocation")
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

    private ToolResponse attemptEscalationAfterHealFailure(
            AgentExecutionContext context,
            ReasoningStep step,
            String toolName,
            Map<String, Object> params,
            ToolResponse.ToolFailure originalFailure,
            ToolResponse.ToolFailure retryFailure,
            List<ReasoningStep> history) {
        String targetRole =
                escalationService.determineEscalationTarget(context.agentRole(), retryFailure.output(), context.goal());
        if (targetRole != null) {
            log.infof(
                    "[PersonaEscalation] Auto-heal failed, escalating %s → %s for session %s",
                    context.agentRole(), targetRole, context.sessionId());
            return attemptEscalation(context, step, toolName, params, retryFailure, targetRole, history);
        }
        return retryFailure;
    }

    private ToolResponse attemptEscalation(
            AgentExecutionContext context,
            ReasoningStep step,
            String toolName,
            Map<String, Object> params,
            ToolResponse.ToolFailure failure,
            String targetRole,
            List<ReasoningStep> history) {
        String originalCode = extractParam(params, "code");
        String language = extractParam(params, "language");
        String originalGoal = context.goal();

        PersonaEscalationService.EscalationResult escalation = escalationService.attemptEscalation(
                context.sessionId(),
                context.userId(),
                context.chatId(),
                context.agentRole(),
                targetRole,
                failure.output(),
                originalGoal,
                originalCode,
                language,
                context.channelType(),
                context.channelPlatformId());

        if (escalation.success()) {
            log.infof("[PersonaEscalation] Escalation succeeded, returning result from %s", escalation.targetPersona());
            return ToolResponse.success(escalation.result());
        }

        log.warnf("[PersonaEscalation] Escalation failed: %s, returning original failure", escalation.errorIfFailed());
        return failure;
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
