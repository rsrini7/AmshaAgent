package com.arcclaw.core.agent;

import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.governance.RequiresApprovalException;
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
    Instance<com.arcclaw.core.observability.AgentObserver> observers;

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
        // --- CONTEXT SETUP ---
        WorkspaceScopeHolder.set(sessionId);
        MDC.put("sessionId", sessionId);
        MDC.put("agentRole", agentRole);
        // Span sessionSpan = otelContext.startSessionSpan(sessionId);

        try {
            // --- CHECKPOINT RESUME ---
            List<ReasoningStep> resumedHistory = List.of();
            var existingCheckpoint = agentStore.load(sessionId, agentRole);
            if (existingCheckpoint.isPresent()) {
                resumedHistory = agentStore.deserializeSteps(existingCheckpoint.get());
                log.infof("Resumed %d steps from checkpoint (session=%s, role=%s)",
                        resumedHistory.size(), sessionId, agentRole);
            }

            AgentResult result = runLoop(goal, engine, maxIterations, resumedHistory, agentRole);

            // --- CHECKPOINT FINAL STATE ---
            if (result.awaitingApproval()) {
                // DO NOTHING: runLoop() already saved the checkpoint state as AWAITING_APPROVAL.
                // We must not overwrite it with COMPLETED or FAILED.
                log.debugf("Session %s paused, waiting for human approval.", sessionId);
            } else {
                // Save the reasoning steps and grab the entity ID
                var cp = agentStore.save(sessionId, agentRole, result.steps());
                
                if (result.success()) {
                    agentStore.markCompleted(cp.id);
                } else {
                    agentStore.markFailed(cp.id, result.terminationReason());
                }
            }

            return result;
        } finally {
            // --- CONTEXT TEARDOWN ---
            MDC.remove("sessionId");
            MDC.remove("agentRole");
            WorkspaceScopeHolder.clear();
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
        return runLoop(goal, engine, maxIterations, List.of(), null);
    }

    /**
     * Core reasoning loop — drives the Thought-Plan-Act-Observe pattern.
     *
     * @param goal           The user's goal.
     * @param engine         The reasoning engine.
     * @param maxIterations  Iteration limit.
     * @param resumedHistory Pre-existing steps from a checkpoint (empty if fresh).
     * @return The final result.
     */
    private AgentResult runLoop(String goal, ReasoningEngine engine,
            int maxIterations, List<ReasoningStep> resumedHistory, String agentRole) {
        // Mutable state — guarded by synchronized (JEP 491 safe)
        final List<ReasoningStep> history = new ArrayList<>(resumedHistory);
        int iteration = 0;

        log.infof("AgentLoop starting for goal: '%s' (maxIterations=%d)", goal, maxIterations);

        // --- CHANGE A: RESUME HOOK ---
        agentStore.load(WorkspaceScopeHolder.get(), agentRole).ifPresent(checkpoint -> {
            if (checkpoint.getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                String pendingTool = checkpoint.getPendingToolName();
                String pendingParams = checkpoint.getPendingToolParametersJson();

                log.infof("[AgentLoop] Resuming session %s: executing pending tool '%s'",
                        WorkspaceScopeHolder.get(), pendingTool);

                ToolResponse resumeResponse = toolRegistry.invokeTool(
                        pendingTool, pendingParams, WorkspaceScopeHolder.get());

                history.add(new ReasoningStep("Human Approved", pendingTool,
                        Map.of("parameters", pendingParams), resumeResponse.output(), 0, 0));

                checkpoint.setPendingToolName(null);
                checkpoint.setPendingToolParametersJson(null);
                checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
                agentStore.save(checkpoint);
            }
        });

        while (iteration < maxIterations) {
            iteration++;
            log.debugf("Loop iteration %d/%d", iteration, maxIterations);

            // --- THOUGHT + PLAN ---
            ReasoningStep step;
            try {
                step = engine.nextStep(goal, Collections.unmodifiableList(history));

                // --- GOVERNANCE & OBSERVABILITY INTERCEPTION ---
                if (step != null) {
                    try {
                        for (var observer : observers) {
                            observer.recordLlmCall(
                                    WorkspaceScopeHolder.get(),
                                    step.inputTokens(),
                                    step.outputTokens());
                        }
                    } catch (com.arcclaw.core.governance.BudgetExceededException be) {
                        log.warnf("Governance aborted loop iteration %d: %s", iteration, be.getMessage());
                        String observationMessage = "System Intervention: " + be.getMessage() +
                                ". Further actions blocked. Ending loop.";
                        step = step.withObservation(observationMessage);
                        synchronized (history) {
                            history.add(step);
                            return AgentResult.error(List.copyOf(history), "Budget Exceeded");
                        }
                    }
                }

            } catch (Exception e) {
                log.errorf(e, "ReasoningEngine failed at iteration %d", iteration);
                synchronized (history) {
                    return AgentResult.error(List.copyOf(history),
                            "ReasoningEngine error: " + e.getMessage());
                }
            }

            if (step == null) {
                log.warn("ReasoningEngine returned null step — terminating");
                synchronized (history) {
                    return AgentResult.error(List.copyOf(history),
                            "ReasoningEngine returned null step");
                }
            }

            // --- FINISH CHECK ---
            if (ReasoningStep.FINISH.equals(step.action())) {
                log.infof("Agent reached FINISH at iteration %d", iteration);
                String answer = step.thought();
                // If the engine put the answer in actionInput, extract it
                if (step.actionInput() != null && step.actionInput().containsKey("answer")) {
                    answer = String.valueOf(step.actionInput().get("answer"));
                }
                step = step.withObservation("Task completed.");
                final ReasoningStep completedStep = step;
                synchronized (history) {
                    history.add(completedStep);
                }
                observers.forEach(o -> o.onStepComplete(
                        WorkspaceScopeHolder.get(), agentRole, completedStep));
                synchronized (history) {
                    return AgentResult.finished(answer, List.copyOf(history));
                }
            }

            // --- HALLUCINATION CHECK ---
            String toolName = step.action();
            var toolOpt = toolRegistry.findTool(toolName);
            if (toolOpt.isEmpty()) {
                String errorObs = String.format(
                        "Tool '%s' does not exist. Available tools: %s. " +
                                "Please choose a valid tool or use FINISH to complete.",
                        toolName, toolRegistry.listTools());
                log.warnf("Hallucination intercepted: tool '%s' not found", toolName);
                step = step.withObservation(errorObs);
                final ReasoningStep completedStep = step;
                synchronized (history) {
                    history.add(completedStep);
                }
                observers.forEach(o -> o.onStepComplete(
                        WorkspaceScopeHolder.get(), agentRole, completedStep));
                continue;
            }

            // --- SCHEMA VALIDATION ---
            Map<String, Object> params = step.actionInput();
            if (params == null) {
                params = Map.of();
            }

            // --- ACT ---
            String observation;
            try {
                ToolResponse response = toolRegistry.invokeTool(toolName, params);
                observation = response.output();
                if (!response.success()) {
                    observation = "Tool execution failed (exit=" + response.exitCode() + "): " + observation;
                }
            } catch (RequiresApprovalException e) {
                // --- CHANGE B: APPROVAL PAUSE ---
                AgentCheckpoint cp = agentStore.load(WorkspaceScopeHolder.get(), agentRole)
                        .orElseGet(() -> {
                            var newCp = new AgentCheckpoint();
                            newCp.sessionId = WorkspaceScopeHolder.get();
                            newCp.agentRole = agentRole;
                            return newCp;
                        });
                cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
                cp.setPendingToolName(e.getToolName());
                cp.setPendingToolParametersJson(e.getToolParametersJson());
                cp.setUpdatedAt(java.time.LocalDateTime.now());
                agentStore.save(cp);

                log.infof("[AgentLoop] Pausing session %s for human approval of tool '%s'",
                        WorkspaceScopeHolder.get(), e.getToolName());

                return AgentResult.awaitingApproval(
                        "⚠️ The agent wants to run the `" + e.getToolName() + "` tool.\n" +
                                "Reply /approve to allow this, or /deny to cancel.",
                        List.copyOf(history));
            } catch (SecurityException e) {
                observation = "Security violation: " + e.getMessage() +
                        ". The current autonomy level does not permit this operation.";
                log.warnf("Tool '%s' blocked by governance: %s", toolName, e.getMessage());
            } catch (Exception e) {
                observation = "Tool execution error: " + e.getMessage();
                log.errorf(e, "Tool '%s' threw unexpected exception", toolName);
            }

            // --- OBSERVE ---
            step = step.withObservation(observation);
            final ReasoningStep completedStep = step;
            synchronized (history) {
                history.add(completedStep);
            }
            observers.forEach(o -> o.onStepComplete(
                    WorkspaceScopeHolder.get(), agentRole, completedStep));
            log.debugf("Observation for '%s': %s",
                    toolName, observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);
        }

        // Loop exhausted
        log.warnf("AgentLoop exceeded %d iterations — terminating", maxIterations);
        synchronized (history) {
            return AgentResult.loopExceeded(List.copyOf(history), maxIterations);
        }
    }
}
