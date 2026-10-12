package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.tool.api.ToolCapability;

import org.jboss.logging.Logger;

/**
 * Validates a requested tool against role capabilities, known tools, and guard rails.
 * Extracted from {@link ToolStepHandler} to keep that class under PMD size limits.
 */
@ApplicationScoped
public class ToolStepValidator {

    private static final Logger log = Logger.getLogger(ToolStepValidator.class);

    private static final Set<String> REACT_KEYWORDS = Set.of("OBSERVE", "THINK", "ACT", "PLAN", "REASON", "THOUGHT");

    private static final Pattern GITHUB_RESOURCE_PATTERN = Pattern.compile(
            "(?i)(github\\.com|[a-zA-Z0-9_.-]{2,}/[a-zA-Z0-9_.-]{2,}(?!\\/)" + "|gh\\s+repo|gist\\.github\\.com)");

    private final ToolRegistry toolRegistry;
    private final Instance<AgentObserver> observers;
    private final Instance<DynamicToolLookup> dynamicToolLookup;
    private final ToolDelegationResolver delegationResolver;

    @Inject
    public ToolStepValidator(
            ToolRegistry toolRegistry,
            Instance<AgentObserver> observers,
            Instance<DynamicToolLookup> dynamicToolLookup,
            ToolDelegationResolver delegationResolver) {
        this.toolRegistry = toolRegistry;
        this.observers = observers;
        this.dynamicToolLookup = dynamicToolLookup;
        this.delegationResolver = delegationResolver;
    }

    ToolStepValidator(
            ToolRegistry toolRegistry,
            Instance<AgentObserver> observers,
            Instance<DynamicToolLookup> dynamicToolLookup) {
        this(toolRegistry, observers, dynamicToolLookup, new ToolDelegationResolver());
    }

    /**
     * Validates the tool requested by {@code step}. Returns a non-null {@link StepDecision} when the
     * request must be rewritten, redirected, or terminated; returns {@code null} when the tool is known
     * and may proceed.
     */
    StepDecision validate(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep step,
            String toolName,
            int consecutiveFailures) {

        // Coordinator-Github Redirect
        if (isCoordinatorGithubViolation(context, toolName, step)) {
            return StepDecision.proceedWithFailures(
                    redirectToDelegate(step, context.sessionId(), AgentRoleEnum.RESEARCHER_ID), consecutiveFailures);
        }

        // Specialist Tool Redirection
        StepDecision specialistDecision = redirectSpecialistTool(context, step, toolName, consecutiveFailures);
        if (specialistDecision != null) {
            return specialistDecision;
        }

        // Known or Dynamic Tool check
        if (ToolStepResolutionUtils.isKnownTool(toolName, toolRegistry, dynamicToolLookup)) {
            var owner = delegationResolver.resolveOwner(context.agentRole(), toolName);
            if (owner.isPresent()) {
                return StepDecision.proceedWithFailures(
                        redirectToDelegate(step, context.sessionId(), owner.get()), consecutiveFailures);
            }
            return null;
        }

        // ReAct keyword or Hallucination
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

    private StepDecision redirectSpecialistTool(
            AgentExecutionContext context, ReasoningStep step, String toolName, int consecutiveFailures) {
        String currentRole = context.agentRole();
        if (!(AgentRoleEnum.COORDINATOR.toScopedName().equals(currentRole)
                        || AgentRoleEnum.RESEARCHER.toScopedName().equals(currentRole))
                || ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)) {
            return null;
        }

        if (ToolCapability.SANDBOX_EXEC.toolName().equals(toolName)) {
            return StepDecision.proceedWithFailures(
                    redirectToDelegate(step, context.sessionId(), AgentRoleEnum.CODER_ID), consecutiveFailures);
        }

        // Coordinator lacks research tools, but researcher HAS them - only redirect coordinator for these
        if (AgentRoleEnum.COORDINATOR.toScopedName().equals(currentRole)) {
            if (ToolCapability.WEB_SEARCH_BRAVE.toolName().equals(toolName)
                    || ToolCapability.WEB_SEARCH_SERPER.toolName().equals(toolName)
                    || ToolCapability.BROWSER_NAVIGATE.toolName().equals(toolName)) {
                return StepDecision.proceedWithFailures(
                        redirectToDelegate(step, context.sessionId(), AgentRoleEnum.RESEARCHER_ID),
                        consecutiveFailures);
            }
        }

        return null;
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
    }
}
