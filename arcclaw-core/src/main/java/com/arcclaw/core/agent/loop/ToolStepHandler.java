package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.AgentStateMachineRunner.StepDecision;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.tool.DynamicToolLookup;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import org.jboss.logging.Logger;

/**
 * Handles tool name validation, 4-phase tool invocation,
 * and the consecutive-failure circuit breaker.
 *
 * FIX §1: TOOL-GUARD silently rewrites coordinator github calls to
 * delegate_to_agent instead of returning a retryEngine hint.
 * This collapses 2–3 wasted iterations (OpenRouter ignores hints) to 1.
 *
 * FIX §2: delegate_to_agent rewrite params use key "agent" (not "role").
 * DelegateToAgentTool.execute() calls params.get("agent") — using "role"
 * caused "Parameter 'agent' is required." to be sent to the user on every
 * cron HITL resume.
 */
@ApplicationScoped
public class ToolStepHandler {

    private static final Logger log = Logger.getLogger(ToolStepHandler.class);

    private static final Set<String> REACT_KEYWORDS = Set.of(
            "OBSERVE", "THINK", "ACT", "PLAN", "REASON", "THOUGHT"
    );

    private static final Set<String> MUTATING_GOAL_KEYWORDS = Set.of(
            "delete", "remove", "cancel", "stop", "modify", "change",
            "update", "edit", "pause", "resume", "clear", "disable"
    );

    private static final Pattern GITHUB_RESOURCE_PATTERN = Pattern.compile(
            "(?i)(github\\.com|[a-zA-Z0-9_.-]{2,}/[a-zA-Z0-9_.-]{2,}(?!\\/)" +
            "|gh\\s+repo|gist\\.github\\.com)");

    @Inject ToolRegistry toolRegistry;
    @Inject Instance<AgentObserver> observers;
    @Inject ToolInvocationGuard guard;
    @Inject ToolInvoker invoker;
    @Inject ChatMemoryWriter writer;
    @Inject Instance<DynamicToolLookup> dynamicToolLookup;
    @Inject ApprovalCheckpointManager checkpointManager;
    @Inject ParallelToolStepExecutor parallelExecutor;

    // ── Validation

    StepDecision validateTool(AgentExecutionContext context, List<ReasoningStep> history,
            ReasoningStep step, int consecutiveFailures) {

        // Parallel batch — bypass single-tool validation, route to executor
        if (step.isParallelBatch()) {
            log.debugf("ToolStepHandler routing parallel batch to " +
                       "ParallelToolStepExecutor for session %s", context.sessionId());
            return StepDecision.proceed(step);
        }

        String toolName = step.action();

        // Rescue: LLM hallucinated a tool named "finish" with a valid answer payload.
        // Rewrite as a proper FINISH step. AgentStateMachineRunner post-validateTool
        // FINISH re-check will route it to handleFinishAction.
        if (ReasoningStep.FINISH.equalsIgnoreCase(toolName)
                && step.actionInput() != null
                && step.actionInput().containsKey(ReasoningStep.ACTION_INPUT_ANSWER_KEY)) {
            log.infof("[ToolStepHandler] Rescued hallucinated 'finish' tool → converting to FINISH for session %s",
                    context.sessionId());
            ReasoningStep rescued = ReasoningStep.pending(
                step.thought(),
                ReasoningStep.FINISH,
                Map.copyOf(step.actionInput()),
                step.inputTokens(),
                step.outputTokens()
            );
            return StepDecision.proceed(rescued);
        }

        // ── TOOL-GUARD: coordinator calling github directly
        // FIX §1: Silently rewrite github → delegate_to_agent instead of
        // returning retryEngine(hint). Hint-based redirect requires LLM compliance —
        // Groq follows it in 1 shot, OpenRouter free-tier ignores it and loops until
        // the iteration budget is exhausted, producing a garbage FINISH answer.
        //
        // FIX §2: Use "agent" as the parameter key, NOT "role".
        // DelegateToAgentTool.execute() does params.get("agent") — using "role"
        // caused every cron HITL resume to fail with "Parameter 'agent' is required."
        if (AgentRoleEnum.COORDINATOR.toScopedName().equals(context.agentRole())
                && ToolCapability.GITHUB.toolName().equals(toolName)) {

            boolean goalHasResource = context.goal() != null
                && GITHUB_RESOURCE_PATTERN.matcher(context.goal()).find();
            
            // AFTER — also pass if a non-empty "target" is explicitly set.
            // target IS the resource (username for gist, user/repo for repo ops).
            // The guard's purpose is to block coordinator calling github with NO
            // resource in mind — if target is set, it clearly knows what to fetch.
            boolean argsHaveResource = step.actionInput() != null
                && (step.actionInput().values().stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .anyMatch(v -> GITHUB_RESOURCE_PATTERN.matcher(v).find())
                    || (step.actionInput().get("target") instanceof String t && !t.isBlank()));

            if (!goalHasResource && !argsHaveResource) {
                log.warnf("[TOOL-GUARD] Coordinator github with no resource in session %s " +
                          "— auto-rewriting to delegate_to_agent (was: retryEngine hint)",
                          context.sessionId());

                String delegateGoal = buildDelegateGoal(
                    toolName,
                    step.actionInput() != null ? step.actionInput() : Map.of());

                ReasoningStep delegateStep = ReasoningStep.pending(
                    step.thought(),
                    ToolCapability.DELEGATE_TO_AGENT.toolName(),
                    Map.of("agent", "researcher", "goal", delegateGoal),
                    0, 0
                );
                return StepDecision.proceedWithFailures(delegateStep, consecutiveFailures);
            }
        }

        if (toolRegistry.findTool(toolName).isPresent())
            return StepDecision.proceedWithFailures(step, consecutiveFailures);

        if (!dynamicToolLookup.isUnsatisfied()
                && dynamicToolLookup.get().isDynamicToolAllowed(toolName)) {
            log.debugf("[ToolStepHandler] '%s' resolved as dynamic MCP tool — proceeding",
                       toolName);
            return StepDecision.proceedWithFailures(step, consecutiveFailures);
        }

        if (REACT_KEYWORDS.contains(toolName.toUpperCase(Locale.ROOT))) {
            log.warnf("[ToolStepHandler] ReAct keyword '%s' used as tool name " +
                      "— ephemeral correction", toolName);
            return StepDecision.retryEngine(String.format(
                    "System: '%s' is a ReAct reasoning keyword, not a tool name. " +
                    "Available tools: %s. Use FINISH to provide your final answer.",
                    toolName, toolRegistry.listTools()), 0);
        }

        String errorObs = String.format(
                "Tool '%s' does not exist. Available tools: %s. " +
                "Please choose a valid tool or use FINISH to complete.",
                toolName, toolRegistry.listTools());
        log.warnf("[ToolStepHandler] Hallucinated tool '%s' intercepted", toolName);
        final ReasoningStep completedStep = step.withObservation(errorObs);
        history.add(completedStep);
        observers.forEach(o -> o.onStepComplete(
            context.sessionId(), context.agentRole(), completedStep));

        consecutiveFailures++;
        if (consecutiveFailures >= 2)
            return StepDecision.terminal(AgentResult.finished(
                    "❌ I encountered repeated errors trying to execute that task. " +
                    "Please rephrase your request or check the parameters.",
                    List.copyOf(history)));
        return StepDecision.retryValidation(consecutiveFailures);
    }

    // ── Invocation

    /** Orchestrates the 4 phases: guard → invoke → observe → resolve outcome. */
    StepDecision invokeToolAndObserve(AgentExecutionContext context,
            List<ReasoningStep> history, ReasoningStep step,
            int consecutiveFailures, List<CompiledSkillEntry> skillSnapshot) {

        // Parallel batch — delegate to ParallelToolStepExecutor
        if (step.isParallelBatch()) {
            return parallelExecutor.execute(context, history, step, consecutiveFailures);
        }

        // Phase 1 — Guard
        StepDecision guardDecision = evaluateGuard(context, history, step, skillSnapshot);
        if (guardDecision != null) return guardDecision;

        // Phase 2 — Invoke
        String toolName = step.action();
        Map<String, Object> params = step.actionInput() != null ? step.actionInput() : Map.of();
        ToolResponse response;
        InvokeResult invokeResult = invokeTool(context, history, step, toolName, params);
        if (invokeResult instanceof InvokeResult.ShortCircuit sc) {
            return sc.decision();
        }
        response = ((InvokeResult.Responded) invokeResult).response();

        if (response.isInfraFailure()) {
            log.errorf("[TOOL-STEP] Infrastructure failure for tool '%s' session=%s: %s",
                    toolName, context.sessionId(), response.output());
            history.add(step.withObservation("INFRA_FAILURE: " + response.output()));
            return StepDecision.terminal(AgentResult.failed(
                    "⚠️ The sandbox execution environment is currently offline. " +
                    "Your code was not executed. Please try again later.",
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(history)));
        }

        // Phase 3 — Observe
        String observation = buildObservation(response);
        final ReasoningStep completedStep = step.withObservation(observation);
        history.add(completedStep);
        observers.forEach(o -> o.onStepComplete(
            context.sessionId(), context.agentRole(), completedStep));
        log.debugf("Observation for '%s': %s", toolName,
                observation.length() > 200
                    ? observation.substring(0, 200) + "..." : observation);

        // Phase 4 — Resolve
        return resolveOutcome(context, toolName, params, response, observation,
                completedStep, history, consecutiveFailures);
    }

    private StepDecision evaluateGuard(AgentExecutionContext context,
            List<ReasoningStep> history, ReasoningStep step,
            List<CompiledSkillEntry> skillSnapshot) {

        if (context.autonomyLevel() == AutonomyLevel.READONLY) {
            log.infof("[ToolStepHandler] READONLY session %s — blocking tool '%s' inline",
                    context.sessionId(), step.action());
            final ReasoningStep blockedStep = step.withObservation(
                    "Tool execution is not permitted in READONLY mode. " +
                    "Answer using only your existing knowledge. " +
                    "Do not attempt to call any tools.");
            history.add(blockedStep);
            observers.forEach(o -> o.onStepComplete(
                context.sessionId(), context.agentRole(), blockedStep));
            return StepDecision.retryValidation(0);
        }

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                context.sessionId(), context.agentRole(), step.action(),
                step.actionInput() != null ? step.actionInput() : Map.of(),
                history, context.goal(), skillSnapshot);

        if (decision instanceof ToolInvocationGuard.GuardDecision.Deduplicate d) {
            final ReasoningStep dedupStep = step.withObservation(d.cachedObservation());
            history.add(dedupStep);
            observers.forEach(o -> o.onStepComplete(
                context.sessionId(), context.agentRole(), dedupStep));
            return StepDecision.proceedWithFailures(dedupStep, 0);
        }

        if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked b) {
            final ReasoningStep blockedStep = step.withObservation(b.observation());
            history.add(blockedStep);
            observers.forEach(o -> o.onStepComplete(
                context.sessionId(), context.agentRole(), blockedStep));

            if (context.sessionId() != null
                    && context.sessionId().contains(":d:")
                    && ToolCapability.GITHUB.toolName().equals(step.action())) {
                log.infof("[TOOL-GUARD] Inner session %s: github blocked " +
                          "— fast-pathing to FINISH", context.sessionId());
                return StepDecision.terminal(
                    AgentResult.finished(b.observation(), List.copyOf(history)));
            }
            return StepDecision.retryValidation(0);
        }

        if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval p) {
            return StepDecision.terminal(
                    checkpointManager.persist(context, p.toolName(), p.paramsJson(), history,
                                              context.channelType(), context.channelPlatformId()));
        }
        return null; // PROCEED
    }

    private InvokeResult invokeTool(AgentExecutionContext context,
            List<ReasoningStep> history, ReasoningStep step,
            String toolName, Map<String, Object> params) {
        try {
            return new InvokeResult.Responded(
                invoker.invoke(toolName, params, context.sessionId()));
        } catch (RequiresApprovalException e) {
            return new InvokeResult.ShortCircuit(StepDecision.terminal(
                    checkpointManager.persist(context,
                        e.getToolName(), e.getToolParametersJson(), history,
                        context.channelType(), context.channelPlatformId())));
        }
    }

    private String buildObservation(ToolResponse response) {
        if (response.success()) return response.output();
        String out = response.output();
        if (out != null && out.startsWith("Tool execution failed:")) return out;
        return "Tool execution failed: " + out + " (exit=" + response.exitCode() + ")";
    }

    /**
     * Resolves the post-tool-invocation outcome.
     *
     * Fast-path exit rules:
     * - schedule_task:               always terminal — handed off to cron system.
     * - manage_schedule (non-list):  always terminal — delete/pause/resume confirmed.
     * - manage_schedule (list):      terminal if goal is list-only (no mutating intent).
     * - delegate_to_agent (success): always terminal — specialist's answer IS the answer.
     */
    private StepDecision resolveOutcome(AgentExecutionContext context, String toolName,
        Map<String, Object> params, ToolResponse response, String observation,
        ReasoningStep completedStep, List<ReasoningStep> history,
        int consecutiveFailures) {

        // schedule_task
        // FIX: Only fast-path on SUCCESS. On failure (e.g. invalid cron expression,
        // blank timezone) fall through so the LLM can self-correct on the next iteration.
        // Previously fast-pathed unconditionally, sending raw error messages like
        // "❌ Tool execution failed: Invalid ID for ZoneOffset" to the user as a
        // final answer with no retry.
        if (ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)) {
            if (response.success()) {
                log.infof("[STATE-MACHINE] Fast-path exit for tool '%s'", toolName);
                String cleanOutput = observation.split("\n\nCRITICAL DIRECTIVE:")[0];
                String finalAnswer = "✅ " + cleanOutput;
                writer.saveAnswer(context.sessionId(), finalAnswer);
                return StepDecision.terminal(
                    AgentResult.finished(finalAnswer, List.copyOf(history)));
            }
            log.warnf("[STATE-MACHINE] schedule_task failed — continuing loop for " +
                    "LLM self-correction session=%s: %s",
                    context.sessionId(), observation);
            // fall through to consecutiveFailures handling
        }

        // manage_schedule
        if (ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName)) {
            String action = String.valueOf(params.getOrDefault("action", "")).toLowerCase();
            if (!"list".equals(action)) {
                log.infof("[STATE-MACHINE] Fast-path exit for tool '%s' action=%s",
                        toolName, action);
                String finalAnswer = (response.success() ? "✅ " : "❌ ") + observation;
                writer.saveAnswer(context.sessionId(), finalAnswer);
                return StepDecision.terminal(
                    AgentResult.finished(finalAnswer, List.copyOf(history)));
            }

            if (isListOnlyGoal(context.goal())) {
                log.debugf("[STATE-MACHINE] manage_schedule list — list-only goal, fast-path exit");
                writer.saveAnswer(context.sessionId(), observation);
                return StepDecision.terminal(
                    AgentResult.finished(observation, List.copyOf(history)));
            }
            log.debugf("[STATE-MACHINE] manage_schedule list — mutating goal, continuing loop");
        }

        // delegate_to_agent
        // response.success() guard is required — without it a failed delegate
        // (e.g. "Parameter 'agent' is required.") gets sent as a finished answer
        // because the error text doesn't start with ⏳/⚠️/"Tool execution failed".
        if (ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)
                && response.success()
                && !observation.startsWith("⚠️")
                && !observation.startsWith("⏳")
                && !observation.startsWith("Tool execution failed")) {
            log.infof("[STATE-MACHINE] Fast-path exit for delegate_to_agent session=%s",
                    context.sessionId());
            writer.saveAnswer(context.sessionId(), observation);
            return StepDecision.terminal(
                AgentResult.finished(observation, List.copyOf(history)));
        }

        // github unavailable
        if (!response.success()
                && toolName.equalsIgnoreCase(ToolCapability.GITHUB.toolName())
                && observation.contains("GitHub integration is not available")) {
            if (context.sessionId() != null && !observation.isBlank()) {
                writer.saveAnswer(context.sessionId(), observation);
            }
            return StepDecision.terminal(
                AgentResult.finished(observation, List.copyOf(history)));
        }

        // Consecutive failure / circuit breaker
        if (!response.success()) {
            consecutiveFailures++;
            if (consecutiveFailures >= 2) {
                log.warnf("[STATE-MACHINE] Circuit breaker tripped session=%s tool=%s failures=%d",
                        context.sessionId(), toolName, consecutiveFailures);
                return StepDecision.terminal(AgentResult.finished(
                        "❌ I encountered repeated errors trying to execute that task. " +
                        "Please rephrase your request or check the parameters.",
                        List.copyOf(history)));
            }
        } else {
            consecutiveFailures = 0;
        }

        return StepDecision.proceedWithFailures(completedStep, consecutiveFailures);
    }

    /**
     * Converts a tool name + args map into a natural-language goal for delegate_to_agent.
     *
     * Examples:
     *   github + {action=gist, command=list, target=rsrini7}
     *     → "Use the github tool with action=gist command=list target=rsrini7
     *        and return the complete results."
     */
    private String buildDelegateGoal(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "Use the " + toolName + " tool and return the complete results.";
        }
        String action  = String.valueOf(args.getOrDefault("action",  "")).strip();
        String command = String.valueOf(args.getOrDefault("command", "")).strip();
        String target  = String.valueOf(args.getOrDefault("target",  "")).strip();

        StringBuilder sb = new StringBuilder("Use the ").append(toolName).append(" tool");
        if (!action.isBlank()  && !"null".equals(action))  sb.append(" with action=").append(action);
        if (!command.isBlank() && !"null".equals(command)) sb.append(" command=").append(command);
        if (!target.isBlank()  && !"null".equals(target))  sb.append(" for user/resource=").append(target);
        sb.append(" and return the complete results.");
        return sb.toString();
        // Produces: "Use the github tool with action=gist command=list for user/resource=rsrini7
        //            and return the complete results."
    }

    private boolean isListOnlyGoal(String goal) {
        if (goal == null || goal.isBlank()) return true;
        String lower = goal.toLowerCase();
        return MUTATING_GOAL_KEYWORDS.stream().noneMatch(lower::contains);
    }

    /** Sealed result of the invokeTool phase. */
    protected sealed interface InvokeResult {
        record Responded(ToolResponse response) implements InvokeResult {}
        record ShortCircuit(StepDecision decision) implements InvokeResult {}
    }
}
