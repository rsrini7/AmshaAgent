package com.arcclaw.core.agent.loop;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

import com.arcclaw.core.agent.loop.AgentStateMachineRunner.StepDecision;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tool.DynamicToolLookup;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.AgentErrorMessageUtil;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Handles individual step execution within the ReAct loop:
 * engine invocation, tool validation, and tool observation.
 * Orchestration lives in {@link AgentStateMachineRunner}.
 */
@ApplicationScoped
public class AgentStepProcessor {
    private static final Logger log = Logger.getLogger(AgentStepProcessor.class);

    private static final Set<String> REACT_KEYWORDS = Set.of(
            "OBSERVE", "THINK", "ACT", "PLAN", "REASON", "THOUGHT"
    );

    @Inject ToolRegistry toolRegistry;
    @Inject StatefulAgentStore agentStore;
    @Inject GovernanceLayer governanceLayer;
    @Inject Instance<AgentObserver> observers;
    @Inject ToolInvocationGuard guard;
    @Inject ToolInvoker invoker;
    @Inject ChatMemoryWriter writer;
    @Inject Instance<DynamicToolLookup> dynamicToolLookup;


    // ── Engine step ──────────────────────────────────────────────────────────

    StepDecision processEngineStep(String sessionId, String goal, ReasoningEngine engine,
            List<ReasoningStep> history, String agentRole, String transientError,
            int llmSchemaErrorCount, int iteration) {
        ReasoningStep step;
        try {
            String currentGoal = transientError != null ? goal + "\n\n" + transientError : goal;
            step = engine.nextStep(sessionId, agentRole, currentGoal,
                    Collections.unmodifiableList(history));

            if (step != null) {
                try {
                    governanceLayer.recordLlmCall(sessionId, step.inputTokens(), step.outputTokens());
                    for (var observer : observers)
                        observer.recordLlmCall(sessionId, step.inputTokens(), step.outputTokens());
                } catch (BudgetWarningException bw) {
                    log.warnf("Budget warning for session %s: %s", sessionId, bw.getMessage());
                    step = step.withObservation("⚠️ BUDGET WARNING: " + bw.getMessage() +
                            " Keep all subsequent responses to 1-2 sentences maximum.");
                    history.add(step);
                } catch (BudgetExceededException be) {
                    log.warnf("Governance aborted loop iteration %d: %s", iteration, be.getMessage());
                    step = step.withObservation("System Intervention: " + be.getMessage() +
                            ". Further actions blocked. Ending loop.");
                    history.add(step);
                    return StepDecision.terminal(AgentResult.failed(
                            AgentErrorMessageUtil.sanitizeErrorMessage(be.getMessage()),
                            AgentResult.FailureCategory.BUDGET_EXCEEDED,
                            List.copyOf(history)));
                }
            }
        } catch (Exception e) {
            return handleEngineException(e, sessionId, llmSchemaErrorCount, iteration, history);
        }

        if (step == null) {
            log.warn("ReasoningEngine returned null step — terminating");
            return StepDecision.terminal(AgentResult.failed(
                    "ReasoningEngine returned null step",
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(history)));
        }
        return StepDecision.proceed(step);
    }

    private StepDecision handleEngineException(Exception e, String sessionId,
            int llmSchemaErrorCount, int iteration, List<ReasoningStep> history) {

        boolean isSchemaError = e instanceof InvalidRequestException
                || (e instanceof HttpException && ((HttpException) e).statusCode() == 400);

        if (isSchemaError) {
            log.warnf("[STATE-MACHINE] LLM schema error session=%s: %s", sessionId, e.getMessage());
            boolean isToolUseFailed = e.getMessage() != null
                    && e.getMessage().contains("tool_use_failed");
            if (isToolUseFailed) {
                log.errorf("[STATE-MACHINE] session %s: tool_use_failed — failing fast", sessionId);
                return StepDecision.terminal(AgentResult.failed(
                        "The requested operation is not supported. " +
                        "Only 'view' and 'list' are valid commands for the github tool.",
                        AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history)));
            }
            llmSchemaErrorCount++;
            if (llmSchemaErrorCount >= 2) {
                log.errorf("[STATE-MACHINE] session %s exceeded schema self-correction limit", sessionId);
                return StepDecision.terminal(AgentResult.failed(
                        "SCHEMA_ERROR", AgentResult.FailureCategory.SCHEMA_ERROR,
                        List.copyOf(history)));
            }
            return StepDecision.retryEngine(
                    "System: Your previous tool call was rejected. Error: " + e.getMessage() +
                    ". Please review the tool schema and correct your arguments.",
                    llmSchemaErrorCount);
        }

        if (e instanceof HttpException && ((HttpException) e).statusCode() == 429) {
            String msg = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
            log.warnf("[STATE-MACHINE] Rate limit hit session=%s: %s", sessionId, msg);
            return StepDecision.terminal(AgentResult.failed(
                    msg, AgentResult.FailureCategory.RATE_LIMIT, List.copyOf(history)));
        }

        if (isNetworkError(e)) {
            log.errorf("ReasoningEngine network failure at iteration %d: %s", iteration, e.getMessage());
            log.debugf(e, "Full stacktrace for network failure in session %s", sessionId);
            return StepDecision.terminal(AgentResult.failed(
                    "⚠️ Unable to reach the AI service. Please check your internet connection and try again.",
                    AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }

        log.errorf("ReasoningEngine failed at iteration %d: %s", iteration, e.getMessage());
        log.debugf(e, "Full stacktrace for ReasoningEngine failure in session %s", sessionId);
        return StepDecision.terminal(AgentResult.failed(
                "ReasoningEngine error: " + e.getMessage(),
                AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
    }

    private boolean isNetworkError(Exception e) {
        if (e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException) return true;
        if (e.getCause() instanceof java.net.ConnectException
                || e.getCause() instanceof java.net.SocketTimeoutException) return true;
        String msg = e.getMessage();
        return msg != null && (msg.contains("ConnectTimeoutException")
                || msg.contains("connection timed out")
                || msg.contains("Connection reset")
                || msg.contains("Connection refused"));
    }

    // ── Tool validation ──────────────────────────────────────────────────────

    StepDecision validateTool(String sessionId, String agentRole, List<ReasoningStep> history,
            ReasoningStep step, String goal, int consecutiveFailures) {
        String toolName = step.action();
        if (toolRegistry.findTool(toolName).isPresent())
            return StepDecision.proceedWithFailures(step, consecutiveFailures);

        // Check dynamic MCP tools before treating as hallucination.
        // DynamicToolLookup may be unsatisfied in test profiles — guard with isUnsatisfied().
        if (!dynamicToolLookup.isUnsatisfied()
                && dynamicToolLookup.get().isDynamicToolAllowed(toolName)) {
            log.debugf("[AgentStepProcessor] '%s' resolved as dynamic MCP tool — proceeding", toolName);
            return StepDecision.proceedWithFailures(step, consecutiveFailures);
        }

        // ReAct keyword leaked as tool name — ephemeral correction, not persisted
        if (REACT_KEYWORDS.contains(toolName.toUpperCase())) {
            log.warnf("[AgentStepProcessor] ReAct keyword '%s' used as tool name — ephemeral correction",
                    toolName);
            return StepDecision.retryEngine(String.format(
                    "System: '%s' is a ReAct reasoning keyword, not a tool name. " +
                    "Available tools: %s. Use FINISH to provide your final answer.",
                    toolName, toolRegistry.listTools()), 0);
        }

        String errorObs = String.format(
                "Tool '%s' does not exist. Available tools: %s. " +
                "Please choose a valid tool or use FINISH to complete.",
                toolName, toolRegistry.listTools());
        log.warnf("[AgentStepProcessor] Hallucinated tool '%s' intercepted", toolName);
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

    // ── Tool invocation ──────────────────────────────────────────────────────

    StepDecision invokeToolAndObserve(String sessionId, String agentRole,
            List<ReasoningStep> history, ReasoningStep step,
            int consecutiveFailures, String goal, List<CompiledSkillEntry> skillSnapshot) {

        String toolName = step.action();
        Map<String, Object> params = step.actionInput() != null ? step.actionInput() : Map.of();

        ToolInvocationGuard.GuardDecision decision =
                guard.evaluate(sessionId, agentRole, toolName, params, history, goal, skillSnapshot);

        if (decision instanceof ToolInvocationGuard.GuardDecision.Deduplicate d) {
            final ReasoningStep dedupStep = step.withObservation(d.cachedObservation());
            history.add(dedupStep);
            observers.forEach(o -> o.onStepComplete(sessionId, agentRole, dedupStep));
            return StepDecision.proceedWithFailures(dedupStep, consecutiveFailures);
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked b) {
            final ReasoningStep blockedStep = step.withObservation(b.observation());
            history.add(blockedStep);
            observers.forEach(o -> o.onStepComplete(sessionId, agentRole, blockedStep));
            return StepDecision.retryValidation(consecutiveFailures);
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval p) {
            return StepDecision.terminal(
                    persistApprovalCheckpoint(sessionId, agentRole,
                            p.toolName(), p.paramsJson(), history));
        }

        ToolResponse response;
        try {
            response = invoker.invoke(toolName, params, sessionId);
        } catch (RequiresApprovalException e) {
            return StepDecision.terminal(
                    persistApprovalCheckpoint(sessionId, agentRole,
                            e.getToolName(), e.getToolParametersJson(), history));
        }

        String observation = response.output();
        boolean stepFailed = !response.success();

        if (stepFailed
                && toolName.equalsIgnoreCase(ToolCapability.GITHUB.toolName())
                && observation != null
                && observation.contains("GitHub integration is not available")) {
            final ReasoningStep completedStep = step.withObservation(observation);
            history.add(completedStep);
            observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));
            if (sessionId != null && !observation.isBlank()) writer.saveAnswer(sessionId, observation);
            return StepDecision.terminal(AgentResult.finished(observation, List.copyOf(history)));
        }

        if (stepFailed && (observation == null || !observation.startsWith("Tool execution failed:")))
            observation = "Tool execution failed: " + observation + " (exit=" + response.exitCode() + ")";

        final ReasoningStep completedStep = step.withObservation(observation);
        history.add(completedStep);
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));
        log.debugf("Observation for '%s': %s", toolName,
                observation != null && observation.length() > 200
                        ? observation.substring(0, 200) + "..." : observation);

        // Check fast-path BEFORE success/failure branching
        if (ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)
                || ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName)) {
            log.infof("[STATE-MACHINE] Fast-path exit for tool '%s'", toolName);
            String cleanOutput = observation.split("\n\nCRITICAL DIRECTIVE:")[0];
            String prefix = response.success() ? "✅ " : "❌ ";
            String finalAnswer = prefix + cleanOutput;
            writer.saveAnswer(sessionId, finalAnswer);
            return StepDecision.terminal(AgentResult.finished(finalAnswer, List.copyOf(history)));
        }                        

        if (stepFailed) {
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
            if (ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)
                    || ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName)) {
                log.infof("[STATE-MACHINE] Fast-path exit for tool '%s'", toolName);
                String cleanOutput = observation.split("\n\nCRITICAL DIRECTIVE:")[0];
                String finalAnswer = "✅ " + cleanOutput;
                writer.saveAnswer(sessionId, finalAnswer);
                return StepDecision.terminal(AgentResult.finished(finalAnswer, List.copyOf(history)));
            }
        }

        return StepDecision.proceedWithFailures(completedStep, consecutiveFailures);
    }

    // ── Checkpoint ───────────────────────────────────────────────────────────

    AgentResult persistApprovalCheckpoint(String sessionId, String agentRole,
            String toolName, String paramsJson, List<ReasoningStep> history) {
        AgentCheckpoint cp = agentStore.load(sessionId, agentRole)
                .orElseGet(() -> {
                    AgentCheckpoint c = new AgentCheckpoint();
                    c.setSessionId(sessionId);
                    c.setAgentRole(agentRole);
                    return c;
                });
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(toolName);
        cp.setPendingToolParametersJson(paramsJson);
        cp.setUpdatedAt(LocalDateTime.now());
        agentStore.save(cp);
        log.infof("[STATE-MACHINE] Pausing session %s for human approval of tool '%s'",
                sessionId, toolName);
        return AgentResult.awaitingApproval(toolName, paramsJson, sessionId, List.copyOf(history));
    }
}
