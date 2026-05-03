package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jboss.logging.Logger;

/**
 * Handles tool name validation, 4-phase tool invocation,
 * and the consecutive-failure circuit breaker.
 *
 * <p>FIX §1: TOOL-GUARD silently rewrites coordinator github calls to
 * delegate_to_agent instead of returning a retryEngine hint.
 * This collapses 2–3 wasted iterations (OpenRouter ignores hints) to 1.
 *
 * <p>FIX §2: delegate_to_agent rewrite params use key "agent" (not "role").
 * DelegateToAgentTool.execute() calls params.get("agent") - using "role"
 * caused "Parameter 'agent' is required." to be sent to the user on every
 * cron HITL resume.
 *
 * <p>Refactored to constructor injection as part of T4-1.
 */
@ApplicationScoped
public class ToolStepHandler {

    private static final Logger log = Logger.getLogger(ToolStepHandler.class);

    private static final Set<String> REACT_KEYWORDS = Set.of(
            "OBSERVE", "THINK", "ACT", "PLAN", "REASON", "THOUGHT"
    );

    private static final Pattern GITHUB_RESOURCE_PATTERN = Pattern.compile(
            "(?i)(github\\.com|[a-zA-Z0-9_.-]{2,}/[a-zA-Z0-9_.-]{2,}(?!\\/)" +
            "|gh\\s+repo|gist\\.github\\.com)");

    private final ToolRegistry toolRegistry;
    private final Instance<AgentObserver> observers;
    private final ToolInvocationGuard guard;
    private final ToolInvoker invoker;
    private final ChatMemoryWriter writer;
    private final Instance<DynamicToolLookup> dynamicToolLookup;
    private final ApprovalCheckpointManager checkpointManager;
    private final ParallelToolStepExecutor parallelExecutor;
    private final MeterRegistry meterRegistry;

    @Inject
    public ToolStepHandler(ToolRegistry toolRegistry,
                           Instance<AgentObserver> observers,
                           ToolInvocationGuard guard,
                           ToolInvoker invoker,
                           ChatMemoryWriter writer,
                           Instance<DynamicToolLookup> dynamicToolLookup,
                           ApprovalCheckpointManager checkpointManager,
                           ParallelToolStepExecutor parallelExecutor,
                           MeterRegistry meterRegistry) {
        this.toolRegistry = toolRegistry;
        this.observers = observers;
        this.guard = guard;
        this.invoker = invoker;
        this.writer = writer;
        this.dynamicToolLookup = dynamicToolLookup;
        this.checkpointManager = checkpointManager;
        this.parallelExecutor = parallelExecutor;
        this.meterRegistry = meterRegistry;
    }

    // ── Validation

    StepDecision validateTool(AgentExecutionContext context, List<ReasoningStep> history,
            ReasoningStep step, int consecutiveFailures) {

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
        // lacks capabilities, redirect to the appropriate specialist agent via delegate_to_agent.
        // Guard: don't redirect if this step is already a delegation to avoid infinite loops.
        String currentRole = context.agentRole();
        if ((AgentRoleEnum.COORDINATOR.toScopedName().equals(currentRole) || AgentRoleEnum.RESEARCHER.toScopedName().equals(currentRole))
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
        boolean goalHasRes = context.goal() != null && GITHUB_RESOURCE_PATTERN.matcher(context.goal()).find();  
        boolean argsHaveRes = step.actionInput() != null
                && (step.actionInput().values().stream().filter(String.class::isInstance).map(String.class::cast)
                        .anyMatch(v -> GITHUB_RESOURCE_PATTERN.matcher(v).find())
                    || (step.actionInput().get("target") instanceof String t && !t.isBlank()));
        return !goalHasRes && !argsHaveRes;
    }

    private ReasoningStep redirectToDelegate(ReasoningStep step, String sessionId, String agent) {
        log.warnf("[TOOL-GUARD] Coordinator %s auto-redirect for session %s to agent %s", step.action(), sessionId, agent);
        String delegateGoal = ToolStepResolutionUtils.buildDelegateGoal(step.action(), step.actionInput() != null ? step.actionInput() : Map.of());
        return ReasoningStep.pending(step.thought(), ToolCapability.DELEGATE_TO_AGENT.toolName(),
            Map.of("agent", agent, "goal", delegateGoal), 0, 0);
    }

    private StepDecision handleUnknownTool(AgentExecutionContext context, List<ReasoningStep> history,
                                         ReasoningStep step, String toolName, int consecutiveFailures) {        
        if (REACT_KEYWORDS.contains(toolName.toUpperCase(Locale.ROOT))) {
            return StepDecision.continueLoop(String.format(
                    "System: '%s' is a ReAct reasoning keyword. Available tools: %s.",
                    toolName, toolRegistry.listTools()), 0);
        }

            String errorObs = String.format("Tool '%s' does not exist. Available tools: %s.",
                toolName, toolRegistry.listTools());
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
    StepDecision invokeToolAndObserve(AgentExecutionContext context,
            List<ReasoningStep> history, ReasoningStep step,
            int consecutiveFailures, List<CompiledSkillEntry> skillSnapshot) {

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

        if (response != null && response.isInfraFailure()) {
            log.errorf("[TOOL-STEP] Infrastructure failure for tool '%s' session=%s: %s",
                    toolName, context.sessionId(), response.output());
            history.add(step.withObservation("INFRA_FAILURE: " + response.output()));
            return StepDecision.terminal(AgentResult.failed(
                    "⚠️ The sandbox execution environment is currently offline. " +
                    "Your code was not executed. Please try again later.",
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(history)));
        }

        // Phase 3 - Observe
        String observation = buildObservation(response);
        final ReasoningStep completedStep = step.withObservation(observation, response != null ? response.success() : false);
        history.add(completedStep);

        // Record for future O(1) deduplication in this turn
        if (response != null && response.success()) {
            guard.recordToolCall(context.sessionId(), toolName, params, observation);
        }

        observers.forEach(o -> o.onStepComplete(
            context.sessionId(), context.agentRole(), completedStep));
        log.debugf("Observation for '%s': %s", toolName,
                observation.length() > 200
                    ? observation.substring(0, 200) + "..." : observation);

        // Phase 4 - Resolve
        return resolveOutcome(context, toolName, params, response, observation,
                completedStep, history, consecutiveFailures);
    }

    private StepDecision evaluateGuard(AgentExecutionContext context,
            List<ReasoningStep> history, ReasoningStep step,
            List<CompiledSkillEntry> skillSnapshot) {

        if (context.autonomyLevel() == AutonomyLevel.READONLY) {
            log.infof("[ToolStepHandler] READONLY session %s - blocking tool '%s' inline",
                    context.sessionId(), step.action());
            final ReasoningStep blockedStep = step.withObservation(
                    "Tool execution is not permitted in READONLY mode. " +
                    "Answer using only your existing knowledge. " +
                    "Do not attempt to call any tools.", false);
            history.add(blockedStep);
            observers.forEach(o -> o.onStepComplete(
                context.sessionId(), context.agentRole(), blockedStep));
            return StepDecision.continueLoop(null, 0);
            // → skips LLM; re-enters inner validation loop immediately (isRetryValidation=true)
        }

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                context.sessionId(), context.agentRole(), step.action(),
                step.actionInput() != null ? step.actionInput() : Map.of(),
                history, context.goal(), skillSnapshot);

        if (decision instanceof ToolInvocationGuard.GuardDecision.Deduplicate d) {
            final ReasoningStep dedupStep = step.withObservation(d.cachedObservation(), true);
            history.add(dedupStep);
            observers.forEach(o -> o.onStepComplete(
                context.sessionId(), context.agentRole(), dedupStep));
            return StepDecision.proceedWithFailures(dedupStep, 0);
        }

        if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked b) {
            final ReasoningStep blockedStep = step.withObservation(b.observation(), false);
            history.add(blockedStep);
            observers.forEach(o -> o.onStepComplete(
                context.sessionId(), context.agentRole(), blockedStep));

            if (context.sessionId() != null
                    && context.sessionId().contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)
                    && ToolCapability.GITHUB.toolName().equals(step.action())) {
                log.infof("[TOOL-GUARD] Inner session %s: github blocked " +
                          "- fast-pathing to FINISH", context.sessionId());
                return StepDecision.terminal(
                    AgentResult.finished(b.observation(), List.copyOf(history)));
            }
            return StepDecision.continueLoop(null, 0);
            // → skips LLM; re-enters inner validation loop immediately (isRetryValidation=true)
        }

        if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval p) {
            List<ReasoningStep> historyAtPause = appendPendingStep(history, step);
            return StepDecision.terminal(
                    checkpointManager.persist(context, p.toolName(), p.paramsJson(), historyAtPause,
                                              context.channelType(), context.channelPlatformId()));
        }
        return null; // PROCEED
    }

    private InvokeResult invokeTool(AgentExecutionContext context,
            List<ReasoningStep> history, ReasoningStep step,
            String toolName, Map<String, Object> params) {
        
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

            return WorkspaceContext.run(context.sessionId(), context.userId(), context.chatId(), 
                    context.autonomyLevel(), context.agentRole(), context.channelType(), () -> {
                
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
            return new InvokeResult.ShortCircuit(StepDecision.terminal(
                    checkpointManager.persist(context,
                        e.getToolName(), e.getToolParametersJson(), historyAtPause,
                        context.channelType(), context.channelPlatformId())));
        } catch (Exception e) {
            exceptionClass = e.getClass().getSimpleName();
            log.errorf("Tool invocation failed for '%s': %s", toolName, e.getMessage());
            return new InvokeResult.Responded(ToolResponse.failure(1, e.getMessage()));
        } finally {
            sample.stop(timer.tag("exception", exceptionClass).register(meterRegistry));
        }
    }

    private List<ReasoningStep> appendPendingStep(List<ReasoningStep> history, ReasoningStep step) {
        List<ReasoningStep> snapshot = new ArrayList<>(history);
        snapshot.add(step);
        return List.copyOf(snapshot);
    }

    private String buildObservation(ToolResponse response) {
        if (response == null) {
            return "Tool invocation returned no response.";
        }
        if (response.success()) return response.output();
        String out = response.output();
        if (out == null) {
            return "Tool invocation returned no response.";
        }
        if (out.startsWith("Tool execution failed:")) return out;
        return "Tool execution failed: " + out + " (exit=" + response.exitCode() + ")";
    }

    /**
     * Resolves the post-tool-invocation outcome.
     */
    private StepDecision resolveOutcome(AgentExecutionContext context, String toolName,
        Map<String, Object> params, ToolResponse response, String observation,
        ReasoningStep completedStep, List<ReasoningStep> history,
        int consecutiveFailures) {

        // 1. Tool-specific fast-paths
        StepDecision decision = null;
        if (ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)) {
            decision = handleScheduleTaskOutcome(context, response, observation, history);
        } else if (ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName)) {
            decision = handleManageScheduleOutcome(context, params, response, observation, history);
        } else if (ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)) {
            decision = handleDelegateOutcome(context, response, observation, history);
        } else if (ToolCapability.GITHUB.toolName().equalsIgnoreCase(toolName)) {
            decision = handleGithubUnavailableOutcome(context, response, observation, history);
        } else if (ToolCapability.BROWSER_NAVIGATE.toolName().equalsIgnoreCase(toolName)) {
            decision = handleBrowserOutcome(context, params, response, observation, history);
        }

        if (decision != null) {
            return decision;
        }

        // 2. Generic Circuit Breaker (evaluates failures and returns PROCEED or TERMINAL)
        return evaluateCircuitBreaker(completedStep, response, consecutiveFailures, context.sessionId(), history);
    }

    private StepDecision handleScheduleTaskOutcome(AgentExecutionContext ctx, ToolResponse res, String obs, List<ReasoningStep> history) {
        if (res != null && res.success()) {
            String cleanOutput = obs.split("\n\nCRITICAL DIRECTIVE:")[0];
            String finalAnswer = "✅ " + cleanOutput;
            writer.saveAnswer(ctx.sessionId(), finalAnswer);
            return StepDecision.terminal(AgentResult.finished(finalAnswer, List.copyOf(history)));
        }
        return null; // fall through
    }

    private StepDecision handleManageScheduleOutcome(AgentExecutionContext ctx, Map<String, Object> params, ToolResponse res, String obs, List<ReasoningStep> history) {
        String action = String.valueOf(params.getOrDefault("action", "")).toLowerCase();
        if (!"list".equals(action)) {
            String finalAnswer = (res != null && res.success() ? "✅ " : "❌ ") + obs;
            writer.saveAnswer(ctx.sessionId(), finalAnswer);
            return StepDecision.terminal(AgentResult.finished(finalAnswer, List.copyOf(history)));
        }
        if (ToolStepResolutionUtils.isListOnlyGoal(ctx.goal())) {
            writer.saveAnswer(ctx.sessionId(), obs);
            return StepDecision.terminal(AgentResult.finished(obs, List.copyOf(history)));
        }
        return null;
    }

    private StepDecision handleDelegateOutcome(AgentExecutionContext ctx, ToolResponse res, String obs, List<ReasoningStep> history) {
        if (res != null && res.success() && !obs.startsWith("⚠️") && !obs.startsWith("⏳") && !obs.startsWith("Tool execution failed")) {
            writer.saveAnswer(ctx.sessionId(), obs);
            return StepDecision.terminal(AgentResult.finished(obs, List.copyOf(history)));
        }
        return null;
    }

    private StepDecision handleGithubUnavailableOutcome(AgentExecutionContext ctx, ToolResponse res, String obs, List<ReasoningStep> history) {
        if (res != null && !res.success() && obs.contains("GitHub integration is not available")) {
            if (ctx.sessionId() != null) writer.saveAnswer(ctx.sessionId(), obs);
            return StepDecision.terminal(AgentResult.finished(obs, List.copyOf(history)));
        }
        return null;
    }

    private StepDecision handleBrowserOutcome(AgentExecutionContext ctx, Map<String, Object> params, ToolResponse res, String obs, List<ReasoningStep> history) {
        if (res != null && !res.success()) {
            String url = String.valueOf(params.getOrDefault("url", ""));
            if (!url.isBlank()) {
                log.infof("[TOOL-STEP] browser_navigate failed for %s - triggering fallback to serper_web_search", url);
                
                String fallbackGoal = "The browser was unable to visit " + url + " directly. " +
                        "Please use serper_web_search to find information about this page or its content.";
                
                // Inject a logical observation that guides the model to fallback
                ReasoningStep fallbackStep = ReasoningStep.observe(ToolCapability.BROWSER_NAVIGATE.toolName(), 
                        "Tool execution failed: " + obs + ". Fallback: " + fallbackGoal,
                        "Browser navigation failed, falling back to search.");
                history.add(fallbackStep);
                
                return StepDecision.retryEngine(fallbackGoal, 0);
            }
        }
        return null;
    }

    private StepDecision evaluateCircuitBreaker(ReasoningStep step, ToolResponse res, int failures, String sid, List<ReasoningStep> history) {
        int newFailures = (res != null && res.success()) ? 0 : failures + 1;
        
        // §Fix: Increase threshold from 2 to 3 to handle transient parameter mismatches
        // where the LLM might need more than one correction turn to align with the schema.
        if (newFailures >= 3) {
            log.warnf("[STATE-MACHINE] Circuit breaker tripped session=%s after %d consecutive failures", sid, newFailures);
            return StepDecision.terminal(AgentResult.finished(
                "❌ I encountered repeated errors (" + newFailures + "). Please rephrase your request or check the tool parameters.", List.copyOf(history)));      
        }
        return StepDecision.proceedWithFailures(step, newFailures);
    }

    /** Sealed result of the invokeTool phase. */
    protected sealed interface InvokeResult {
        record Responded(ToolResponse response) implements InvokeResult {}
        record ShortCircuit(StepDecision decision) implements InvokeResult {}
    }
}
