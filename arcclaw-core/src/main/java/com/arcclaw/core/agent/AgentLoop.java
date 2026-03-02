package com.arcclaw.core.agent;

import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.audit.AuditHashUtil;
import com.arcclaw.core.audit.ComplianceViolationException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stateful Thought-Plan-Act-Observe reasoning loop with resilience guardrails.
 * <p>
 * The loop drives an {@link ReasoningEngine} through iterative reasoning steps,
 * executing tools via {@link ToolRegistry}, until the engine emits a
 * {@code "FINISH"} action or the iteration limit is reached.
 * <p>
 * <strong>Guardrails:</strong>
 * <ul>
 * <li><b>Loop Protection</b>: {@code maxIterations} (default 5) prevents
 * runaway loops.</li>
 * <li><b>Hallucination Check</b>: Unknown tool names are intercepted — an error
 * observation is injected into context so the LLM can self-correct.</li>
 * <li><b>Schema Validation</b>: Null/empty params are caught before tool
 * invocation.</li>
 * </ul>
 * <p>
 * All shared state ({@code history}, {@code iterationCount}) is guarded by
 * {@code synchronized} per JEP 491 — safe for virtual threads on JDK 25.
 */
@ApplicationScoped
public class AgentLoop {

    private static final Logger log = Logger.getLogger(AgentLoop.class);
    private static final int DEFAULT_MAX_ITERATIONS = 5;

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    AgentOtelContext otelContext;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Inject
    Instance<AgentObserver> observers;


    @Inject
    GovernanceLayer governanceLayer;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    ObjectMapper objectMapper;

    // Use word boundaries (\b) so "pr" doesn't match "prepare" or "approve"
    // Compile once for performance.
    // NOTE: Intentionally using string concatenation, NOT a Java text block.
    // Text blocks preserve embedded newlines and leading whitespace, which
    // would corrupt the regex alternation groups and silently break matching.
    // If a text block is needed, add (?x) free-spacing flag — but plain
    // concatenation is clearer and zero-surprise for future maintainers.
    private static final Pattern GITHUB_TRIGGER_PATTERN =
    Pattern.compile(
        "(?i)\\b(repo|repository|github|gh|fetch|gist|clone|" +
        "pull\\s+request|pr|get|show|describe|description|desc|" +
        "star|fork|issue|commit|branch|tag|readme|release)\\b"
    );

    /**
     * Execute the reasoning loop with default iteration limit (no lifecycle
     * context).
     */
    public AgentResult run(String goal, ReasoningEngine engine) {
        return run(goal, engine, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Lifecycle-aware entry point — sets up context, resumes from checkpoint,
     * and tears down on completion.
     * <p>
     * Context setup (try):
     * <ol>
     * <li>{@code WorkspaceScopeHolder.set(sessionId)} — activates CDI scope</li>
     * <li>MDC: pushes {@code sessionId} for structured logging</li>
     * <li>OTel: starts a session root span</li>
     * <li>Checkpoint: loads existing history from {@code StatefulAgentStore}</li>
     * </ol>
     * <p>
     * Context teardown (finally):
     * <ol>
     * <li>Clear MDC</li>
     * <li>Clear {@code WorkspaceScopeHolder}</li>
     * <li>End OTel session span</li>
     * </ol>
     *
     * @param sessionId     The workspace session ID.
     * @param agentRole     The agent role identifier.
     * @param goal          The user's goal / task description.
     * @param engine        The reasoning engine (LLM abstraction).
     * @param maxIterations Maximum loop iterations before forced termination.
     * @return The final result with reasoning trace.
     */
    @ActivateRequestContext
    public AgentResult run(String sessionId, String agentRole, String goal,
            ReasoningEngine engine, int maxIterations) {
        // --- Resolve agentRole string to enum at API boundary ---
        AgentRoleEnum resolvedRole;
        try {
            resolvedRole = AgentRoleEnum.fromString(agentRole);
        } catch (IllegalArgumentException e) {
            log.warnf("[AgentLoop] Unknown agentRole '%s' — defaulting to COORDINATOR", agentRole);
            resolvedRole = AgentRoleEnum.COORDINATOR;
        }
        final String canonicalRole = resolvedRole.toScopedName();

        // --- CONTEXT SETUP ---
        MDC.put("sessionId", sessionId);
        MDC.put("agentRole", canonicalRole);

        AgentResult result = null;
        try {
            // --- CHECKPOINT RESUME ---
            List<ReasoningStep> resumedHistory = List.of();
            var existingCheckpoint = agentStore.load(sessionId, canonicalRole);
            if (existingCheckpoint.isPresent()) {
                resumedHistory = agentStore.deserializeSteps(existingCheckpoint.get());
                log.infof("Resumed %d steps from checkpoint (session=%s, role=%s)",
                        resumedHistory.size(), sessionId, canonicalRole);
            }

            result = runLoop(sessionId, goal, engine, maxIterations, resumedHistory, canonicalRole);

            // --- CHECKPOINT FINAL STATE ---
            if (result.awaitingApproval()) {
                log.debugf("Session %s paused, waiting for human approval.", sessionId);
            } else {
                var cp = agentStore.save(sessionId, canonicalRole, result.steps());
                if (result.success()) {
                    agentStore.markCompleted(cp.getId());
                } else {
                    agentStore.markFailed(cp.getId(), result.terminationReason());
                }
            }

            return result;
        } catch (ComplianceViolationException cve) {
            // FAIL-CLOSED: mandatory audit write failed — agent MUST halt immediately.
            // No further LLM calls or tool executions permitted with a broken audit trail.
            log.errorf("[SECURITY ALERT] ComplianceViolationException for session=%s eventType=%s — halting agent.",
                    sessionId, cve.getEventType());
            result = AgentResult.error(List.of(), "COMPLIANCE_VIOLATION: " + cve.getMessage());
            try {
                agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                    cp.setStatus(CheckpointStatus.COMPROMISED);
                    agentStore.save(cp);
                });
            } catch (Exception ignored) {}
            throw cve; // re-throw to physically halt the call stack
        } catch (Exception e) {
            result = AgentResult.error(List.of(), "SYSTEM_ERROR: " + e.getMessage());
            try {
                agentStore.load(sessionId, canonicalRole).ifPresent(cp ->
                    agentStore.markFailed(cp.getId(), "SYSTEM_ERROR: " + e.getMessage())
                );
            } catch (Exception ignored) {}
            throw e;
        } finally {
            if (result != null) {
                String goalHash = AuditHashUtil.sha256hex(goal);
                String userId = MDC.get("userId") != null ? (String) MDC.get("userId") : "unknown";
                final AgentResult finalResult = result;
                observers.forEach(o -> o.onLoopComplete(sessionId, userId, canonicalRole, goalHash, finalResult));
            }
            // --- CONTEXT TEARDOWN ---
            MDC.remove("sessionId");
            MDC.remove("agentRole");
            otelContext.endSessionSpan(sessionId);
        }
    }

    /**
     * Execute the Thought-Plan-Act-Observe reasoning loop.
     * <p>
     * Each iteration:
     * <ol>
     * <li>Ask the {@link ReasoningEngine} for the next step (thought + action)</li>
     * <li>If action = {@code "FINISH"} → return the final answer</li>
     * <li>Validate the action (tool exists, params valid)</li>
     * <li>Execute via {@link ToolRegistry#invokeTool(String, Map)}</li>
     * <li>Record the observation and continue</li>
     * </ol>
     *
     * @param goal          The user's goal / task description.
     * @param engine        The reasoning engine (LLM abstraction).
     * @param maxIterations Maximum loop iterations before forced termination.
     * @return The final result with reasoning trace.
     */
    public AgentResult run(String goal, ReasoningEngine engine, int maxIterations) {
        return runLoop(WorkspaceScopeHolder.getRequired(), goal, engine, maxIterations, List.of(), null);
    }

    /**
     * Core reasoning loop — drives the Thought-Plan-Act-Observe pattern.
     *
     * @param sessionId      Current workspace session.
     * @param goal           The user's goal.
     * @param engine         The reasoning engine.
     * @param maxIterations  Iteration limit.
     * @param resumedHistory Pre-existing steps from a checkpoint (empty if fresh).
     * @return The final result.
     */
    private AgentResult runLoop(String sessionId, String goal, ReasoningEngine engine,
            int maxIterations, List<ReasoningStep> resumedHistory, String agentRole) {
        // Mutable state — guarded by synchronized (JEP 491 safe)
        final List<ReasoningStep> history = new ArrayList<>(resumedHistory);
        int iteration = 0;
        int llmSchemaErrorCount = 0;
        int consecutiveFailures = 0; // NEW: Circuit breaker counter
        String transientError = null;

        log.infof("AgentLoop starting for goal: '%s' (maxIterations=%d)", goal, maxIterations);

        saveGoalToChatMemory(sessionId, goal);

        AgentResult resumeResult = handleResumeHook(sessionId, agentRole, history);
        if (resumeResult != null) {
            return resumeResult;
        }

        while (iteration < maxIterations) {
            iteration++;
            log.debugf("Loop iteration %d/%d", iteration, maxIterations);

            // --- THOUGHT + PLAN ---
            StepDecision engineDecision = processEngineStep(sessionId, goal, engine, history, agentRole, transientError, llmSchemaErrorCount, iteration);
            if (engineDecision.type() == DecisionType.TERMINAL) return engineDecision.result();
            if (engineDecision.type() == DecisionType.CONTINUE) {
                transientError = engineDecision.transientError();
                llmSchemaErrorCount = engineDecision.llmSchemaErrorCount();
                continue;
            }
            ReasoningStep step = engineDecision.step();
            transientError = null; // Reset if we got a valid step

            // --- FINISH CHECK ---
            if (ReasoningStep.FINISH.equals(step.action())) {
                return handleFinishAction(sessionId, agentRole, history, step, iteration);
            }

            // --- TOOL VALIDATION ---
            StepDecision validationDecision = validateTool(sessionId, agentRole, history, step, goal, consecutiveFailures);
            if (validationDecision.type() == DecisionType.TERMINAL) return validationDecision.result();
            if (validationDecision.type() == DecisionType.CONTINUE) {
                consecutiveFailures = validationDecision.consecutiveFailures();
                continue;
            }
            step = validationDecision.step();

            // --- ACT & OBSERVE ---
            StepDecision invocationDecision = invokeToolAndObserve(sessionId, agentRole, history, step, consecutiveFailures);
            if (invocationDecision.type() == DecisionType.TERMINAL) return invocationDecision.result();
            consecutiveFailures = invocationDecision.consecutiveFailures();
        }

        // Loop exhausted
        log.warnf("AgentLoop exceeded %d iterations — terminating", maxIterations);
        synchronized (history) {
            return AgentResult.loopExceeded(List.copyOf(history), maxIterations);
        }
    }

    private void saveGoalToChatMemory(String sessionId, String goal) {
        // --- SPRINT 2: Save user prompt BEFORE anything else happens ---
        if (sessionId != null && goal != null && !goal.isEmpty()) {
            if (!"The tool execution has been approved and completed. Read the tool's output. If it is an error, tell me. Otherwise, provide the final answer to my original request.".equals(goal)
                && !"Resuming task after approval".equals(goal)
                && !("Resuming task after denial").equals(goal)) {

                List<ChatMessage> currentHistory = chatMemoryStore.getMessages(sessionId);
                if (currentHistory == null) currentHistory = new ArrayList<>();
                else currentHistory = new ArrayList<>(currentHistory);

                // Avoid duplicating if we somehow re-enter with the same goal
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

    private AgentResult handleResumeHook(String sessionId, String agentRole, List<ReasoningStep> history) {
        var resumeOpt = agentStore.load(sessionId, agentRole);
        if (resumeOpt.isPresent()) {
            var checkpoint = resumeOpt.get();
            if (checkpoint.getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                String pendingTool = checkpoint.getPendingToolName();
                String pendingParams = checkpoint.getPendingToolParametersJson();

                // The goal passed in is the safeInput from TelegramAgentHandler:
                // "Resuming task after denial" signals user sent /deny <cronId>
                // We check the current goal via the session's latest UserMessage in ChatMemory
                List<ChatMessage> mem = chatMemoryStore.getMessages(sessionId);
                boolean isDenial = !mem.isEmpty()
                        && mem.get(mem.size() - 1) instanceof UserMessage lastMsg
                        && lastMsg.text() != null
                        && lastMsg.text().toLowerCase().contains("denial");

                if (isDenial) {
                    log.infof("[AgentLoop] User denied tool execution for session %s, tool %s",
                            sessionId, pendingTool);
                    checkpoint.setStatus(CheckpointStatus.FAILED);
                    checkpoint.setFailReason("User denied tool execution.");
                    checkpoint.setPendingToolName(null);
                    checkpoint.setPendingToolParametersJson(null);
                    agentStore.save(checkpoint);
                    synchronized (history) {
                        return AgentResult.finished(
                                "❌ Tool execution was denied and the scheduled task has been cancelled.",
                                List.copyOf(history));
                    }
                }

                // --- Normal approval path ---
                log.infof("[AgentLoop] Resuming session %s executing pending tool %s",
                        sessionId, pendingTool);

                try {
                    ToolResponse resumeResponse = WorkspaceContext.runWithFullAutonomy(
                            sessionId, WorkspaceContext.userId(), WorkspaceContext.chatId(), agentRole,
                            () -> toolRegistry.invokeTool(pendingTool, pendingParams, sessionId));
                    synchronized (history) {
                        history.add(new ReasoningStep(
                                "Human Approved", pendingTool,
                                Map.of("parameters", pendingParams),
                                resumeResponse.output(), 0, 0));
                    }
                    checkpoint.setPendingToolName(null);
                    checkpoint.setPendingToolParametersJson(null);
                    checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
                    agentStore.save(checkpoint);
                } catch (Exception e) {
                    checkpoint.setStatus(CheckpointStatus.FAILED);
                    checkpoint.setPendingToolName(null);
                    checkpoint.setPendingToolParametersJson(null);
                    agentStore.save(checkpoint);
                    log.errorf("Resume tool execution failed for session=%s tool=%s: %s",
                            sessionId, pendingTool, e.getMessage());
                    synchronized (history) {
                        return AgentResult.error(List.copyOf(history),
                                "TOOL_EXECUTION_FAILED: " + e.getMessage());
                    }
                }
            }
        }
        return null;
    }


    private AgentResult handleFinishAction(String sessionId, String agentRole, List<ReasoningStep> history, ReasoningStep step, int iteration) {
        // --- FINISH CHECK ---
        log.infof("Agent reached FINISH at iteration %d", iteration);
        String answer = step.thought();
        // If the engine put the answer in actionInput, extract it
        if (step.actionInput() != null && step.actionInput().containsKey("answer")) {
            answer = String.valueOf(step.actionInput().get("answer"));
        }

        // --- SPRINT 2: SAVE COMPLETED AI ANSWER TO CHAT MEMORY STORE ---
        if (sessionId != null && answer != null) {
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
        observers.forEach(o -> o.onStepComplete(
                sessionId, agentRole, completedStep));
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
        // --- THOUGHT + PLAN ---
        ReasoningStep step;
        try {
            String currentGoal = goal;
            if (transientError != null) {
                currentGoal = goal + "\n\n" + transientError;
            }
            step = engine.nextStep(sessionId, agentRole, currentGoal, Collections.unmodifiableList(history));

            // --- GOVERNANCE & OBSERVABILITY INTERCEPTION ---
            if (step != null) {
                try {
                    // GOVERNANCE: Explicit budget check BEFORE other observers
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
                        return StepDecision.terminal(AgentResult.error(List.copyOf(history), "Budget Exceeded"));
                    }
                }
            }
        } catch (Exception e) {
            boolean isSchemaError = e instanceof InvalidRequestException;
            if (!isSchemaError && e instanceof HttpException) {
                isSchemaError = ((HttpException) e).statusCode() == 400;
            }

            if (isSchemaError) {
                log.warnf("[AGENT-LOOP] LLM API rejected tool call schema for session %s: %s", sessionId, e.getMessage());
                llmSchemaErrorCount++;
                if (llmSchemaErrorCount >= 2) {
                    log.errorf("[AGENT-LOOP] session %s exceeded schema self-correction limit", sessionId);
                    synchronized (history) {
                        return StepDecision.terminal(AgentResult.error(List.copyOf(history), "SCHEMA_ERROR"));
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
                return StepDecision.terminal(AgentResult.error(List.copyOf(history), "ReasoningEngine error: " + e.getMessage()));
            }
        }

        if (step == null) {
            log.warn("ReasoningEngine returned null step — terminating");
            synchronized (history) {
                return StepDecision.terminal(AgentResult.error(List.copyOf(history), "ReasoningEngine returned null step"));
            }
        }

        return StepDecision.proceed(step);
    }

    private StepDecision validateTool(String sessionId, String agentRole, List<ReasoningStep> history, ReasoningStep step, String goal, int consecutiveFailures) {
        // --- HALLUCINATION CHECK ---
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

            // Increment circuit breaker for hallucination loops
            consecutiveFailures++;
            if (consecutiveFailures >= 2) {
                synchronized (history) {
                    return StepDecision.terminal(AgentResult.finished("❌ I encountered repeated errors trying to execute that task. " +
                            "Please rephrase your request or check the parameters.", List.copyOf(history)));
                }
            }
            return StepDecision.retryValidation(consecutiveFailures);
        }

        // Verify the original user goal actually mentions a GitHub-related keyword
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
        // --- SCHEMA VALIDATION ---
        String toolName = step.action();
        Map<String, Object> params = step.actionInput() != null ? step.actionInput() : Map.of();
        String observation;
        boolean stepFailed = false;

        try {
            // ✅ HITL gate – role-based, autonomy-aware, exempt scheduling tools
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
            log.infof("[AgentLoop] Pausing session %s for human approval of tool '%s'", sessionId, e.getToolName());
            return StepDecision.terminal(AgentResult.awaitingApproval(
                    "The agent wants to run the `" + e.getToolName() + "` tool. " +
                    "Reply `approve` to allow this, or `deny` to cancel.",
                    List.copyOf(history)));
        } catch (SecurityException e) {
            observation = "Security violation: " + e.getMessage() + ". The current autonomy level does not permit this operation.";
            log.warnf("Tool '%s' blocked by governance: %s", toolName, e.getMessage());
            stepFailed = true; // Mark as failed
        } catch (Exception e) {
            observation = "Tool execution error: " + e.getMessage();
            log.errorf(e, "Tool '%s' threw unexpected exception", toolName);
            stepFailed = true; // Mark as failed
        }

        // --- OBSERVE ---
        final ReasoningStep completedStep = step.withObservation(observation);
        synchronized (history) {
            history.add(completedStep);
        }
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));
        log.debugf("Observation for '%s': %s", toolName, observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

        // --- NEW: CIRCUIT BREAKER LOGIC ---
        if (stepFailed) {
            consecutiveFailures++;
            if (consecutiveFailures >= 2) {
                log.warnf("[AGENT-LOOP] Circuit breaker tripped for session %s. Tool '%s' failed %d times consecutively.",
                        sessionId, toolName, consecutiveFailures);
                synchronized (history) {
                    return StepDecision.terminal(AgentResult.finished(
                            "❌ I encountered repeated errors trying to execute that task. " +
                            "Please rephrase your request or check the parameters.",
                            List.copyOf(history)));
                }
            }
        } else {
            consecutiveFailures = 0; // Reset counter on successful tool execution
            
            // --- NEW: FAST-PATH EXIT FOR SCHEDULING ---
            // If we successfully scheduled a task, skip the final LLM summary call to save tokens!
            if ("scheduletask".equals(toolName) || "manageschedule".equals(toolName)) {
                log.infof("[AgentLoop] Fast-path exit triggered for tool '%s' to save LLM quota.", toolName);
                
                // Clean up the observation string (remove the Jedi Mind Trick text)
                String cleanOutput = observation.split("\n\nCRITICAL DIRECTIVE:")[0];
                String finalFastPathAnswer = "✅ " + cleanOutput;

                // FIX: Save the fast-path answer to persistent chat memory so the LLM remembers it!
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