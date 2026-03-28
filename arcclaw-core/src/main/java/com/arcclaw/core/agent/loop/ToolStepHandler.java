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
 */
@ApplicationScoped
public class ToolStepHandler {

    private static final Logger log = Logger.getLogger(ToolStepHandler.class);

    private static final Set<String> REACT_KEYWORDS = Set.of(
            "OBSERVE", "THINK", "ACT", "PLAN", "REASON", "THOUGHT"
    );

    // FIX Bug 1: Keywords that indicate the user wants to act on the list,
    // not just view it. Only these goals continue the loop after manage_schedule list.
    private static final Set<String> MUTATING_GOAL_KEYWORDS = Set.of(
            "delete", "remove", "cancel", "stop", "modify", "change",
            "update", "edit", "pause", "resume", "clear", "disable"
    );

    private static final Pattern GITHUB_RESOURCE_PATTERN = Pattern.compile(
            "(?i)(github\\.com|[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+|gh\\s+repo|gist)");

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
            // Execution happens in invokeToolAndObserve()
        }

        String toolName = step.action();
        
        // Component 5: Coordinator GitHub Misuse Guard
        if (AgentRoleEnum.COORDINATOR.toScopedName().equals(context.agentRole())
                && ToolCapability.GITHUB.toolName().equals(toolName)) {
            boolean goalHasResource = context.goal() != null && GITHUB_RESOURCE_PATTERN.matcher(context.goal()).find();
            boolean argsHaveResource = step.actionInput() != null
                    && step.actionInput().values().stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .anyMatch(v -> GITHUB_RESOURCE_PATTERN.matcher(v).find());
                        
            if (!goalHasResource && !argsHaveResource) {
                log.warnf("[TOOL-GUARD] Coordinator github with no GitHub resource " +
                          "in session %s — redirecting to delegate_to_agent",
                          context.sessionId());
                return StepDecision.retryEngine(
                    "System: You called the github tool, but the user's goal contains no GitHub " +
                    "repository or resource reference. " +
                    "If the user asked you to involve a specialist (e.g. 'ask Shiva', 'delegate'), " +
                    "use delegate_to_agent instead. " +
                    "If the answer is already in context, use FINISH directly.", 0);
            }
        }
        if (toolRegistry.findTool(toolName).isPresent())
            return StepDecision.proceedWithFailures(step, consecutiveFailures);

        if (!dynamicToolLookup.isUnsatisfied()
                && dynamicToolLookup.get().isDynamicToolAllowed(toolName)) {
            log.debugf("[ToolStepHandler] '%s' resolved as dynamic MCP tool — proceeding", toolName);
            return StepDecision.proceedWithFailures(step, consecutiveFailures);
        }

        if (REACT_KEYWORDS.contains(toolName.toUpperCase(Locale.ROOT))) {
            log.warnf("[ToolStepHandler] ReAct keyword '%s' used as tool name — ephemeral correction", toolName);
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
        observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), completedStep));

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
            log.errorf("[TOOL-STEP] Infrastructure failure for tool '%s' session=%s — " +
                    "short-circuiting LLM to prevent hallucination: %s",
                    toolName, context.sessionId(), response.output());
            history.add(step.withObservation("INFRA_FAILURE: " + response.output()));
            return StepDecision.terminal(AgentResult.failed(
                    "⚠️ The sandbox execution environment is currently offline. " +
                    "Your code was not executed. Please try again later or contact the administrator.",
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(history)));
        }

        // Phase 3 — Observe
        String observation = buildObservation(response);
        final ReasoningStep completedStep = step.withObservation(observation);
        history.add(completedStep);
        observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), completedStep));
        log.debugf("Observation for '%s': %s", toolName,
                observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

        // Phase 4 — Resolve (params forwarded so manage_schedule can branch on action)
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
            observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), blockedStep));
            return StepDecision.retryValidation(0);
        }

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                context.sessionId(), context.agentRole(), step.action(),
                step.actionInput() != null ? step.actionInput() : Map.of(),
                history, context.goal(), skillSnapshot);

        if (decision instanceof ToolInvocationGuard.GuardDecision.Deduplicate d) {
            final ReasoningStep dedupStep = step.withObservation(d.cachedObservation());
            history.add(dedupStep);
            observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), dedupStep));
            return StepDecision.proceedWithFailures(dedupStep, 0);
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked b) {
            final ReasoningStep blockedStep = step.withObservation(b.observation());
            history.add(blockedStep);
            observers.forEach(o -> o.onStepComplete(context.sessionId(), context.agentRole(), blockedStep));
            return StepDecision.retryValidation(0);
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval p) {
            return StepDecision.terminal(
                    checkpointManager.persist(context, p.toolName(), p.paramsJson(), history));
        }
        return null; // PROCEED
    }

    private InvokeResult invokeTool(AgentExecutionContext context,
            List<ReasoningStep> history, ReasoningStep step,
            String toolName, Map<String, Object> params) {
        try {
            return new InvokeResult.Responded(invoker.invoke(toolName, params, context.sessionId()));
        } catch (RequiresApprovalException e) {
            return new InvokeResult.ShortCircuit(StepDecision.terminal(
                    checkpointManager.persist(context,
                            e.getToolName(), e.getToolParametersJson(), history)));
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
     *                                continues loop ONLY if goal contains delete/modify/etc.
     *                                This prevents the LLM from appending conversational
     *                                follow-ups ("Would you like to delete or modify it?")
     *                                when the user simply asked to see the task list.
     */
    private StepDecision resolveOutcome(AgentExecutionContext context, String toolName,
            Map<String, Object> params,
            ToolResponse response, String observation,
            ReasoningStep completedStep, List<ReasoningStep> history,
            int consecutiveFailures) {

        if (ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)) {
            log.infof("[STATE-MACHINE] Fast-path exit for tool '%s'", toolName);
            String cleanOutput = observation.split("\n\nCRITICAL DIRECTIVE:")[0];
            String finalAnswer = (response.success() ? "✅ " : "❌ ") + cleanOutput;
            writer.saveAnswer(context.sessionId(), finalAnswer);
            return StepDecision.terminal(AgentResult.finished(finalAnswer, List.copyOf(history)));
        }

        if (ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName)) {
            String action = String.valueOf(params.getOrDefault("action", "")).toLowerCase();
            if (!"list".equals(action)) {
                // delete / pause / resume — always terminal
                log.infof("[STATE-MACHINE] Fast-path exit for tool '%s' action=%s", toolName, action);
                String finalAnswer = (response.success() ? "✅ " : "❌ ") + observation;
                writer.saveAnswer(context.sessionId(), finalAnswer);
                return StepDecision.terminal(AgentResult.finished(finalAnswer, List.copyOf(history)));
            }

            // FIX Bug 1: list — fast-path if the original goal has no mutating intent.
            // Only continue the loop when the user's goal explicitly involves
            // deleting, modifying, or acting on a task (e.g. "delete the oldest task").
            if (isListOnlyGoal(context.goal())) {
                log.debugf("[STATE-MACHINE] manage_schedule list — list-only goal, fast-path exit");
                writer.saveAnswer(context.sessionId(), observation);
                return StepDecision.terminal(AgentResult.finished(observation, List.copyOf(history)));
            }
            // Goal contains mutating intent — continue so LLM can act on the listed tasks
            log.debugf("[STATE-MACHINE] manage_schedule list — mutating goal detected, continuing loop");
        }

        if (!response.success()
                && toolName.equalsIgnoreCase(ToolCapability.GITHUB.toolName())
                && observation.contains("GitHub integration is not available")) {
            if (context.sessionId() != null && !observation.isBlank()) writer.saveAnswer(context.sessionId(), observation);
            return StepDecision.terminal(AgentResult.finished(observation, List.copyOf(history)));
        }

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
     * Returns true if the goal contains no keywords that imply the user wants to
     * act on the listed tasks. When true, manage_schedule list fast-paths to exit
     * instead of continuing the loop (which causes LLM conversational follow-ups).
     */
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
