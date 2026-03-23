package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.AgentStateMachineRunner.StepDecision;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.observability.AgentObserver;
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

    @Inject ToolRegistry toolRegistry;
    @Inject Instance<AgentObserver> observers;
    @Inject ToolInvocationGuard guard;
    @Inject ToolInvoker invoker;
    @Inject ChatMemoryWriter writer;
    @Inject Instance<DynamicToolLookup> dynamicToolLookup;
    @Inject ApprovalCheckpointManager checkpointManager;

    // ── Validation

    StepDecision validateTool(String sessionId, String agentRole, List<ReasoningStep> history,
            ReasoningStep step, String goal, int consecutiveFailures) {
        String toolName = step.action();
        if (toolRegistry.findTool(toolName).isPresent())
            return StepDecision.proceedWithFailures(step, consecutiveFailures);

        if (!dynamicToolLookup.isUnsatisfied()
                && dynamicToolLookup.get().isDynamicToolAllowed(toolName)) {
            log.debugf("[ToolStepHandler] '%s' resolved as dynamic MCP tool — proceeding", toolName);
            return StepDecision.proceedWithFailures(step, consecutiveFailures);
        }

        if (REACT_KEYWORDS.contains(toolName.toUpperCase())) {
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
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));

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
    StepDecision invokeToolAndObserve(String sessionId, String agentRole,
            List<ReasoningStep> history, ReasoningStep step,
            int consecutiveFailures, String goal, List<CompiledSkillEntry> skillSnapshot) {

        // Phase 1 — Guard
        StepDecision guardDecision = evaluateGuard(sessionId, agentRole, history, step, goal, skillSnapshot);
        if (guardDecision != null) return guardDecision;

        // Phase 2 — Invoke
        String toolName = step.action();
        Map<String, Object> params = step.actionInput() != null ? step.actionInput() : Map.of();
        ToolResponse response;
        try {
            response = invokeTool(sessionId, agentRole, history, step, toolName, params, goal);
        } catch (FastExitException e) {
            return e.decision;
        }

        // Phase 3 — Observe
        String observation = buildObservation(response);
        final ReasoningStep completedStep = step.withObservation(observation);
        history.add(completedStep);
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));
        log.debugf("Observation for '%s': %s", toolName,
                observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

        // Phase 4 — Resolve
        return resolveOutcome(sessionId, toolName, response, observation,
                completedStep, history, consecutiveFailures);
    }

    private StepDecision evaluateGuard(String sessionId, String agentRole,
            List<ReasoningStep> history, ReasoningStep step,
            String goal, List<CompiledSkillEntry> skillSnapshot) {

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                sessionId, agentRole, step.action(),
                step.actionInput() != null ? step.actionInput() : Map.of(),
                history, goal, skillSnapshot);

        if (decision instanceof ToolInvocationGuard.GuardDecision.Deduplicate d) {
            final ReasoningStep dedupStep = step.withObservation(d.cachedObservation());
            history.add(dedupStep);
            observers.forEach(o -> o.onStepComplete(sessionId, agentRole, dedupStep));
            return StepDecision.proceedWithFailures(dedupStep, 0);
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked b) {
            final ReasoningStep blockedStep = step.withObservation(b.observation());
            history.add(blockedStep);
            observers.forEach(o -> o.onStepComplete(sessionId, agentRole, blockedStep));
            return StepDecision.retryValidation(0);
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval p) {
            return StepDecision.terminal(
                    checkpointManager.persist(sessionId, agentRole, p.toolName(), p.paramsJson(), history, goal));
        }
        return null; // PROCEED
    }

    private ToolResponse invokeTool(String sessionId, String agentRole,
            List<ReasoningStep> history, ReasoningStep step,
            String toolName, Map<String, Object> params, String goal) {
        try {
            return invoker.invoke(toolName, params, sessionId);
        } catch (RequiresApprovalException e) {
            throw new FastExitException(StepDecision.terminal(
                    checkpointManager.persist(sessionId, agentRole,
                            e.getToolName(), e.getToolParametersJson(), history, goal)));
        }
    }

    private String buildObservation(ToolResponse response) {
        if (response.success()) return response.output();
        String out = response.output();
        if (out != null && out.startsWith("Tool execution failed:")) return out;
        return "Tool execution failed: " + out + " (exit=" + response.exitCode() + ")";
    }

    private StepDecision resolveOutcome(String sessionId, String toolName,
            ToolResponse response, String observation,
            ReasoningStep completedStep, List<ReasoningStep> history,
            int consecutiveFailures) {

        if (ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)
                || ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName)) {
            log.infof("[STATE-MACHINE] Fast-path exit for tool '%s'", toolName);
            String cleanOutput = observation.split("\n\nCRITICAL DIRECTIVE:")[0];
            String finalAnswer = (response.success() ? "✅ " : "❌ ") + cleanOutput;
            writer.saveAnswer(sessionId, finalAnswer);
            return StepDecision.terminal(AgentResult.finished(finalAnswer, List.copyOf(history)));
        }

        if (!response.success()
                && toolName.equalsIgnoreCase(ToolCapability.GITHUB.toolName())
                && observation.contains("GitHub integration is not available")) {
            if (sessionId != null && !observation.isBlank()) writer.saveAnswer(sessionId, observation);
            return StepDecision.terminal(AgentResult.finished(observation, List.copyOf(history)));
        }

        if (!response.success()) {
            consecutiveFailures++;
            if (consecutiveFailures >= 2) {
                log.warnf("[STATE-MACHINE] Circuit breaker tripped session=%s tool=%s failures=%d",
                        sessionId, toolName, consecutiveFailures);
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

    /** Unchecked signal to short-circuit invokeTool() when approval is required mid-call. */
    static class FastExitException extends RuntimeException {
        final StepDecision decision;
        FastExitException(StepDecision decision) {
            super(null, null, true, false); // suppress stacktrace — control flow only
            this.decision = decision;
        }
    }
}
