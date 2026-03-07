package com.arcclaw.core.agent.loop;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;

import org.jboss.logging.Logger;

import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.util.AgentErrorMessageUtil;

/**
 * High-level runner that executes the Thought-Plan-Act-Observe reasoning loop.
 * <p>
 * Extracted from {@link AgentLoop} in Phase 47 Tier 3 to separate reasoning
 * logic from lifecycle management (MDC, OTel, persistence).
 */
@ApplicationScoped
public class AgentStateMachineRunner {
    private static final Logger log = Logger.getLogger(AgentStateMachineRunner.class);

    @Inject ToolRegistry toolRegistry;
    @Inject StatefulAgentStore agentStore;
    @Inject GovernanceLayer governanceLayer;
    @Inject Instance<AgentObserver> observers;
    @Inject ToolInvocationGuard guard;
    @Inject ToolInvoker invoker;
    @Inject ChatMemoryWriter writer;

    public AgentResult run(AgentExecutionContext context, ReasoningEngine engine, List<ReasoningStep> resumedHistory) {
        List<ReasoningStep> history = new ArrayList<>(resumedHistory);
        int iteration = 0;
        int llmSchemaErrorCount = 0;
        int consecutiveFailures = 0;
        String transientError = null;

        log.infof("StateMachine starting session=%s goal=%s maxIterations=%d",
                context.sessionId(), context.goal(), context.maxIterations());

        saveGoalToChatMemory(context.sessionId(), context.goal());

        while (iteration < context.maxIterations()) {
            iteration++;
            log.debugf("StateMachine iteration %d/%d", iteration, context.maxIterations());

            StepDecision engineDecision = processEngineStep(
                    context.sessionId(), context.goal(), engine, history,
                    context.agentRole(), transientError, llmSchemaErrorCount, iteration);

            if (engineDecision.type() == DecisionType.TERMINAL) return engineDecision.result();
            if (engineDecision.type() == DecisionType.CONTINUE) {
                transientError = engineDecision.transientError();
                llmSchemaErrorCount = engineDecision.llmSchemaErrorCount();
                continue;
            }

            ReasoningStep step = engineDecision.step();
            transientError = null;

            if (ReasoningStep.FINISH.equals(step.action()))
                return handleFinishAction(context.sessionId(), context.agentRole(),
                        history, step, iteration);

            StepDecision validationDecision = validateTool(
                    context.sessionId(), context.agentRole(), history,
                    step, context.goal(), consecutiveFailures);
            if (validationDecision.type() == DecisionType.TERMINAL) return validationDecision.result();
            if (validationDecision.type() == DecisionType.CONTINUE) {
                consecutiveFailures = validationDecision.consecutiveFailures();
                continue;
            }
            step = validationDecision.step();

            StepDecision invocationDecision = invokeToolAndObserve(
                    context.sessionId(), context.agentRole(), history, step,
                    consecutiveFailures, context.goal());
            if (invocationDecision.type() == DecisionType.TERMINAL) return invocationDecision.result();
            consecutiveFailures = invocationDecision.consecutiveFailures();
        }

        log.warnf("StateMachine exceeded %d iterations for session=%s",
                context.maxIterations(), context.sessionId());
        return AgentResult.loopExceeded(context.maxIterations(), List.copyOf(history));
    }

    private void saveGoalToChatMemory(String sessionId, String goal) {
        writer.saveGoal(sessionId, goal);
    }

    private AgentResult handleFinishAction(String sessionId, String agentRole, List<ReasoningStep> history, ReasoningStep step, int iteration) {
        log.infof("Agent reached FINISH at iteration %d", iteration);
        String answer = step.thought();
        if (step.actionInput() != null && step.actionInput().containsKey("answer")) {
            answer = String.valueOf(step.actionInput().get("answer"));
        }

        if (sessionId != null && answer != null && !answer.isBlank()) {
            writer.saveAnswer(sessionId, answer);
        }

        final ReasoningStep completedStep = step.withObservation("Task completed.");
        history.add(completedStep);
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));
        return AgentResult.finished(answer, List.copyOf(history));
    }

    /**
     * Consolidates logic for pausing the loop for human approval of a tool call.
     */
    private AgentResult persistApprovalCheckpoint(
            String sessionId, String agentRole,
            String toolName, String paramsJson,
            List<ReasoningStep> history) {
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
        return AgentResult.awaitingApproval(toolName, paramsJson, sessionId,
                List.copyOf(history));
    }

    private enum DecisionType { CONTINUE, TERMINAL, PROCEED }

    private record StepDecision(
            DecisionType type,
            AgentResult result,
            ReasoningStep step,
            String transientError,
            int llmSchemaErrorCount,
            int consecutiveFailures
    ) {
        static StepDecision proceed(ReasoningStep step) {
            return new StepDecision(DecisionType.PROCEED, null, step, null, 0, 0);
        }
        static StepDecision terminal(AgentResult result) {
            return new StepDecision(DecisionType.TERMINAL, result, null, null, 0, 0);
        }
        static StepDecision retryEngine(String transientError, int llmSchemaErrorCount) {
            return new StepDecision(DecisionType.CONTINUE, null, null, transientError, llmSchemaErrorCount, 0);
        }
        static StepDecision retryValidation(int consecutiveFailures) {
            return new StepDecision(DecisionType.CONTINUE, null, null, null, 0, consecutiveFailures);
        }
        static StepDecision proceedWithFailures(ReasoningStep step, int consecutiveFailures) {
            return new StepDecision(DecisionType.PROCEED, null, step, null, 0, consecutiveFailures);
        }
    }

    private StepDecision processEngineStep(String sessionId, String goal, ReasoningEngine engine,
                                           List<ReasoningStep> history, String agentRole, String transientError, 
                                           int llmSchemaErrorCount, int iteration) {
        ReasoningStep step;
        try {
            String currentGoal = goal;
            if (transientError != null) {
                currentGoal = goal + "\n\n" + transientError;
            }
            step = engine.nextStep(sessionId, agentRole, currentGoal, Collections.unmodifiableList(history));

            if (step != null) {
                try {
                    governanceLayer.recordLlmCall(sessionId, step.inputTokens(), step.outputTokens());
                    for (var observer : observers) {
                        observer.recordLlmCall(sessionId, step.inputTokens(), step.outputTokens());
                    }
                } catch (BudgetWarningException bw) {
                    log.warnf("Budget warning for session %s: %s", sessionId, bw.getMessage());
                    String warningObservation = "⚠️ BUDGET WARNING: " + bw.getMessage() +
                            " Keep all subsequent responses to 1-2 sentences maximum.";
                    step = step.withObservation(warningObservation);
                    history.add(step);
                } catch (BudgetExceededException be) {
                    log.warnf("Governance aborted loop iteration %d: %s", iteration, be.getMessage());
                    String observationMessage = "System Intervention: " + be.getMessage() +
                            ". Further actions blocked. Ending loop.";
                    step = step.withObservation(observationMessage);
                    history.add(step);
                    String failReason = AgentErrorMessageUtil.sanitizeErrorMessage(be.getMessage());
                    return StepDecision.terminal(AgentResult.failed(failReason, AgentResult.FailureCategory.BUDGET_EXCEEDED, List.copyOf(history)));
                }
            }
        } catch (Exception e) {
            boolean isSchemaError = e instanceof InvalidRequestException;
            if (!isSchemaError && e instanceof HttpException) {
                isSchemaError = ((HttpException) e).statusCode() == 400;
            }

            if (isSchemaError) {
                log.warnf("[STATE-MACHINE] LLM API rejected tool call schema for session %s: %s", sessionId, e.getMessage());
                llmSchemaErrorCount++;
                if (llmSchemaErrorCount >= 2) {
                    log.errorf("[STATE-MACHINE] session %s exceeded schema self-correction limit", sessionId);
                        return StepDecision.terminal(AgentResult.failed("SCHEMA_ERROR", AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history)));
                }
                String nextTransientError = "System: Your previous tool call was rejected by the API because the " +
                        "arguments did not match the required schema. Error: " + e.getMessage() +
                        ". Please review the tool schema and correct your arguments. " +
                        "Only 'view' and 'list' are valid commands for the github tool.";
                return StepDecision.retryEngine(nextTransientError, llmSchemaErrorCount);
            }

            if (e instanceof HttpException && ((HttpException) e).statusCode() == 429) {
                String humanMessage = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
                log.warnf("[STATE-MACHINE] Rate limit hit for session %s: %s", sessionId, humanMessage);
                    return StepDecision.terminal(AgentResult.failed(
                        humanMessage,  // already formatted — no re-sanitization needed downstream
                        AgentResult.FailureCategory.SYSTEM_ERROR,
                        List.copyOf(history)));
            }

            log.errorf(e, "ReasoningEngine failed at iteration %d", iteration);
            return StepDecision.terminal(AgentResult.failed("ReasoningEngine error: " + e.getMessage(), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }

        if (step == null) {
            log.warn("ReasoningEngine returned null step — terminating");
            return StepDecision.terminal(AgentResult.failed("ReasoningEngine returned null step", AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }

        return StepDecision.proceed(step);
    }

    private StepDecision validateTool(String sessionId, String agentRole, List<ReasoningStep> history, ReasoningStep step, String goal, int consecutiveFailures) {
        String toolName = step.action();
        var toolOpt = toolRegistry.findTool(toolName);
        if (toolOpt.isEmpty()) {
            String errorObs = String.format(
                    "Tool '%s' does not exist. Available tools: %s. " +
                            "Please choose a valid tool or use FINISH to complete.",
                    toolName, toolRegistry.listTools());
            log.warnf("Hallucination intercepted: tool '%s' not found", toolName);
            final ReasoningStep completedStep = step.withObservation(errorObs);
            history.add(completedStep);
            observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));

            consecutiveFailures++;
            if (consecutiveFailures >= 2) {
                    return StepDecision.terminal(AgentResult.finished("❌ I encountered repeated errors trying to execute that task. " +
                            "Please rephrase your request or check the parameters.", List.copyOf(history)));
            }
            return StepDecision.retryValidation(consecutiveFailures);
        }

        return StepDecision.proceedWithFailures(step, consecutiveFailures);
    }

    private StepDecision invokeToolAndObserve(String sessionId, String agentRole,
                                              List<ReasoningStep> history, ReasoningStep step,
                                              int consecutiveFailures, String goal) {

        String toolName = step.action();
        Map<String, Object> params = step.actionInput() != null ? step.actionInput() : Map.of();

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(sessionId, agentRole, toolName, params, history, goal);

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
                    persistApprovalCheckpoint(sessionId, agentRole, p.toolName(), p.paramsJson(), history));
        }

        // Decision is Allow
        ToolResponse response;
        try {
            response = invoker.invoke(toolName, params, sessionId);
        } catch (RequiresApprovalException e) {
            return StepDecision.terminal(
                    persistApprovalCheckpoint(sessionId, agentRole, e.getToolName(), e.getToolParametersJson(), history));
        }

        String observation = response.output();
        boolean stepFailed = !response.success();
        if (stepFailed && !observation.startsWith("Tool execution failed:")) {
            observation = "Tool execution failed: " + observation + " (exit=" + response.exitCode() + ")";
        }

        final ReasoningStep completedStep = step.withObservation(observation);
        history.add(completedStep);
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));
        log.debugf("Observation for '%s': %s", toolName, observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

        if (stepFailed) {
            consecutiveFailures++;
            if (consecutiveFailures >= 2) {
                log.warnf("[STATE-MACHINE] Circuit breaker tripped for session %s. Tool '%s' failed %d times consecutively.",
                        sessionId, toolName, consecutiveFailures);
                return StepDecision.terminal(AgentResult.finished(
                        "❌ I encountered repeated errors trying to execute that task. " +
                                "Please rephrase your request or check the parameters.",
                        List.copyOf(history)));
            }
        } else {
            consecutiveFailures = 0;

            if (ToolCapability.SCHEDULE_TASK.toolName().equals(toolName) || ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName)) {
                log.infof("[STATE-MACHINE] Fast-path exit triggered for tool '%s' to save LLM quota.", toolName);
                String cleanOutput = observation.split("\n\nCRITICAL DIRECTIVE:")[0];
                String finalFastPathAnswer = "✅ " + cleanOutput;
                writer.saveAnswer(sessionId, finalFastPathAnswer);

                return StepDecision.terminal(AgentResult.finished(finalFastPathAnswer, List.copyOf(history)));
            }
        }

        return StepDecision.proceedWithFailures(completedStep, consecutiveFailures);
    }
}
