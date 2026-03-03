package com.arcclaw.core.agent;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

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
    @Inject ChatMemoryStore chatMemoryStore;
    @Inject Instance<AgentObserver> observers;
    @Inject RoleRegistry roleRegistry;
    @Inject ObjectMapper objectMapper;

    // Use word boundaries (\b) so "pr" doesn't match "prepare" or "approve"
    private static final Pattern GITHUB_TRIGGER_PATTERN =
            Pattern.compile(
                    "(?i)\\b(repo|repository|github|gh|fetch|gist|clone|" +
                            "pull\\s+request|pr|get|show|describe|description|desc|" +
                            "star|fork|issue|commit|branch|tag|readme|release)\\b"
            );

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
                    context.sessionId(), context.agentRole(), history, step, consecutiveFailures);
            if (invocationDecision.type() == DecisionType.TERMINAL) return invocationDecision.result();
            consecutiveFailures = invocationDecision.consecutiveFailures();
        }

        log.warnf("StateMachine exceeded %d iterations for session=%s",
                context.maxIterations(), context.sessionId());
        synchronized (history) {
            return AgentResult.loopExceeded(context.maxIterations(), List.copyOf(history));
        }
    }

    private void saveGoalToChatMemory(String sessionId, String goal) {
        if (sessionId != null && goal != null && !goal.isEmpty()) {
            if (!"The tool execution has been approved and completed. Read the tool's output. If it is an error, tell me. Otherwise, provide the final answer to my original request.".equals(goal)
                    && !"Resuming task after approval".equals(goal)
                    && !("Resuming task after denial").equals(goal)) {

                List<ChatMessage> currentHistory = chatMemoryStore.getMessages(sessionId);
                if (currentHistory == null) currentHistory = new ArrayList<>();
                else currentHistory = new ArrayList<>(currentHistory);

                boolean alreadySaved = false;
                if (!currentHistory.isEmpty()) {
                    ChatMessage lastMsg = currentHistory.get(currentHistory.size() - 1);
                    if (lastMsg instanceof UserMessage && lastMsg.toString().contains(goal)) {
                        alreadySaved = true;
                    }
                }
                if (!alreadySaved) {
                    currentHistory.add(UserMessage.from(goal));
                    chatMemoryStore.updateMessages(sessionId, currentHistory);
                }
            }
        }
    }

    private AgentResult handleFinishAction(String sessionId, String agentRole, List<ReasoningStep> history, ReasoningStep step, int iteration) {
        log.infof("Agent reached FINISH at iteration %d", iteration);
        String answer = step.thought();
        if (step.actionInput() != null && step.actionInput().containsKey("answer")) {
            answer = String.valueOf(step.actionInput().get("answer"));
        }

        if (sessionId != null && answer != null && !answer.isBlank()) {
            List<ChatMessage> currentHistory = chatMemoryStore.getMessages(sessionId);
            if (currentHistory == null) {
                currentHistory = new ArrayList<>();
            } else {
                currentHistory = new ArrayList<>(currentHistory);
            }
            currentHistory.add(AiMessage.from(answer));
            chatMemoryStore.updateMessages(sessionId, currentHistory);
        }

        final ReasoningStep completedStep = step.withObservation("Task completed.");
        synchronized (history) {
            history.add(completedStep);
        }
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));
        synchronized (history) {
            return AgentResult.finished(answer, List.copyOf(history));
        }
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
                                           List<ReasoningStep> history, String agentRole, String transientError, int llmSchemaErrorCount, int iteration) {
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
                } catch (com.arcclaw.core.governance.BudgetWarningException bw) {
                    log.warnf("Budget warning for session %s: %s", sessionId, bw.getMessage());
                    String warningObservation = "⚠️ BUDGET WARNING: " + bw.getMessage() +
                            " Keep all subsequent responses to 1-2 sentences maximum.";
                    step = step.withObservation(warningObservation);
                    synchronized (history) { history.add(step); }
                } catch (com.arcclaw.core.governance.BudgetExceededException be) {
                    log.warnf("Governance aborted loop iteration %d: %s", iteration, be.getMessage());
                    String observationMessage = "System Intervention: " + be.getMessage() +
                            ". Further actions blocked. Ending loop.";
                    step = step.withObservation(observationMessage);
                    synchronized (history) {
                        history.add(step);
                        return StepDecision.terminal(AgentResult.failed("Budget Exceeded: " + be.getMessage(), AgentResult.FailureCategory.BUDGET_EXCEEDED, List.copyOf(history)));
                    }
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
                    synchronized (history) {
                        return StepDecision.terminal(AgentResult.failed("SCHEMA_ERROR", AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history)));
                    }
                }
                String nextTransientError = "System: Your previous tool call was rejected by the API because the " +
                        "arguments did not match the required schema. Error: " + e.getMessage() +
                        ". Please review the tool schema and correct your arguments. " +
                        "Only 'view' and 'list' are valid commands for the github tool.";
                return StepDecision.retryEngine(nextTransientError, llmSchemaErrorCount);
            }

            log.errorf(e, "ReasoningEngine failed at iteration %d", iteration);
            synchronized (history) {
                return StepDecision.terminal(AgentResult.failed("ReasoningEngine error: " + e.getMessage(), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
            }
        }

        if (step == null) {
            log.warn("ReasoningEngine returned null step — terminating");
            synchronized (history) {
                return StepDecision.terminal(AgentResult.failed("ReasoningEngine returned null step", AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
            }
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
            synchronized (history) {
                history.add(completedStep);
            }
            observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));

            consecutiveFailures++;
            if (consecutiveFailures >= 2) {
                synchronized (history) {
                    return StepDecision.terminal(AgentResult.finished("❌ I encountered repeated errors trying to execute that task. " +
                            "Please rephrase your request or check the parameters.", List.copyOf(history)));
                }
            }
            return StepDecision.retryValidation(consecutiveFailures);
        }

        if ("github".equals(toolName)) {
            if (!GITHUB_TRIGGER_PATTERN.matcher(goal).find()) {
                String guardObs = "Tool 'github' was blocked by the Tool Intent Guard: " +
                        "the user's request does not reference a GitHub resource. " +
                        "Please answer the user's question using your own knowledge " +
                        "and respond with FINISH. Do not call any tools.";

                log.warnf("[TOOL-INTENT-GUARD] github blocked session=%s goal='%s'",
                        sessionId, goal.length() > 100 ? goal.substring(0, 100) + "..." : goal);

                final ReasoningStep guardedStep = step.withObservation(guardObs);
                synchronized (history) { history.add(guardedStep); }
                observers.forEach(o -> o.onStepComplete(sessionId, agentRole, guardedStep));
                return StepDecision.retryValidation(consecutiveFailures);
            }
        }

        return StepDecision.proceedWithFailures(step, consecutiveFailures);
    }

    private StepDecision invokeToolAndObserve(String sessionId, String agentRole, List<ReasoningStep> history, ReasoningStep step, int consecutiveFailures) {
        String toolName = step.action();
        Map<String, Object> params = step.actionInput() != null ? step.actionInput() : Map.of();
        String observation;
        boolean stepFailed = false;

        try {
            AgentRoleEnum resolvedRole = AgentRoleEnum.fromString(agentRole);
            boolean isSchedulingTool = "scheduletask".equals(toolName) || "manageschedule".equals(toolName);

            if (roleRegistry.requiresApprovalForTools(resolvedRole)
                    && !isSchedulingTool
                    && !AutonomyLevel.FULL.equals(WorkspaceContext.autonomy())) {

                String paramsJson = objectMapper.writeValueAsString(params);
                throw new RequiresApprovalException(toolName, paramsJson, sessionId);
            }

            ToolResponse response = toolRegistry.invokeTool(toolName, params);
            observation = response.output();
            if (!response.success()) {
                observation = "Tool execution failed: " + observation +
                        " (exit=" + response.exitCode() + ")";
                stepFailed = true;
            }
        } catch (RequiresApprovalException e) {
            AgentCheckpoint cp = agentStore.load(sessionId, agentRole)
                    .orElseGet(() -> {
                        AgentCheckpoint c = new AgentCheckpoint();
                        c.setSessionId(sessionId);
                        c.setAgentRole(agentRole);
                        return c;
                    });
            cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            cp.setPendingToolName(e.getToolName());
            cp.setPendingToolParametersJson(e.getToolParametersJson());
            cp.setUpdatedAt(java.time.LocalDateTime.now());
            agentStore.save(cp);
            log.infof("[STATE-MACHINE] Pausing session %s for human approval of tool '%s'", sessionId, e.getToolName());
            return StepDecision.terminal(AgentResult.awaitingApproval(
                    e.getToolName(),
                    e.getToolParametersJson(),
                    sessionId,
                    List.copyOf(history)));
        } catch (SecurityException e) {
            observation = "Security violation: " + e.getMessage() + ". The current autonomy level does not permit this operation.";
            log.warnf("Tool '%s' blocked by governance: %s", toolName, e.getMessage());
            stepFailed = true;
        } catch (Exception e) {
            observation = "Tool execution error: " + e.getMessage();
            log.errorf(e, "Tool '%s' threw unexpected exception", toolName);
            stepFailed = true;
        }

        final ReasoningStep completedStep = step.withObservation(observation);
        synchronized (history) {
            history.add(completedStep);
        }
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));
        log.debugf("Observation for '%s': %s", toolName, observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

        if (stepFailed) {
            consecutiveFailures++;
            if (consecutiveFailures >= 2) {
                log.warnf("[STATE-MACHINE] Circuit breaker tripped for session %s. Tool '%s' failed %d times consecutively.",
                        sessionId, toolName, consecutiveFailures);
                synchronized (history) {
                    return StepDecision.terminal(AgentResult.finished(
                            "❌ I encountered repeated errors trying to execute that task. " +
                                    "Please rephrase your request or check the parameters.",
                            List.copyOf(history)));
                }
            }
        } else {
            consecutiveFailures = 0;

            if ("scheduletask".equals(toolName) || "manageschedule".equals(toolName)) {
                log.infof("[STATE-MACHINE] Fast-path exit triggered for tool '%s' to save LLM quota.", toolName);

                String cleanOutput = observation.split("\n\nCRITICAL DIRECTIVE:")[0];
                String finalFastPathAnswer = "✅ " + cleanOutput;

                if (sessionId != null) {
                    List<ChatMessage> currentHistory = chatMemoryStore.getMessages(sessionId);
                    if (currentHistory == null) {
                        currentHistory = new ArrayList<>();
                    } else {
                        currentHistory = new ArrayList<>(currentHistory);
                    }
                    currentHistory.add(AiMessage.from(finalFastPathAnswer));
                    chatMemoryStore.updateMessages(sessionId, currentHistory);
                }

                synchronized (history) {
                    return StepDecision.terminal(AgentResult.finished(finalFastPathAnswer, List.copyOf(history)));
                }
            }
        }

        return StepDecision.proceedWithFailures(completedStep, consecutiveFailures);
    }
}
