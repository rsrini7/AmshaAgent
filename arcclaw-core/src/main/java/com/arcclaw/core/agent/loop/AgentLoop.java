package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.audit.AuditHashUtil;
import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Lifecycle shell for the ArcClaw agent reasoning loop.
 *
 * <p>Handles session lifecycle only: MDC setup/teardown, OpenTelemetry span management,
 * checkpoint load/resume, compliance violation handling, and observer callbacks.
 * All reasoning iteration logic (Thought-Plan-Act-Observe, tool invocation, circuit breaker,
 * schema self-healing) is delegated to {@link AgentStateMachineRunner}.
 *
 * <p>All external callers ({@code AgentResource}, {@code AgentDispatchHandler},
 * {@code AgentCronScheduler}, {@code ChatCommand}, {@code AskCommand}) call
 * {@link #run(String, String, String, ReasoningEngine, int)} unchanged.
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
    AgentStateMachineRunner stateMachineRunner;

    @Inject
    CheckpointResumeHandler checkpointResumeHandler;

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
            List<ReasoningStep> resumedHistory = new ArrayList<>();
            var existingCheckpoint = agentStore.load(sessionId, canonicalRole);
            if (existingCheckpoint.isPresent()) {
                var checkpoint = existingCheckpoint.get();
                CheckpointStatus status = checkpoint.getStatus();

                // Only resume history if the turn is actively pending or interrupted.
                // If status is COMPLETED or FAILED (which happens after a /reset), 
                // we MUST ignore old ReAct steps and start a fresh turn.
                if (status == CheckpointStatus.IN_PROGRESS || status == CheckpointStatus.AWAITING_APPROVAL) {
                    List<ReasoningStep> loadedSteps = agentStore.deserializeSteps(checkpoint);
                    if (loadedSteps != null) {
                        resumedHistory.addAll(loadedSteps);
                    }
                    log.infof("Resumed %d steps from active checkpoint (status=%s)", resumedHistory.size(), status);
                } else {
                    // This log line will now appear after you /reset and then say "hi"
                    log.debugf("Ignoring terminal checkpoint (status=%s). Starting fresh turn.", status);
                }
            }

            // resumedHistory is mutable (from deserializeSteps) when status == AWAITING_APPROVAL —
            // the L134 condition guarantees this. On a fresh turn, resumedHistory = List.of()
            // (immutable) but handleResumeHook returns early (goal doesn't start with "resuming task"),
            // so history.add() inside the hook is never reached on an immutable list. Safe.
            AgentResult resumeResult = checkpointResumeHandler.handle(
                    sessionId, canonicalRole, goal, resumedHistory);
            if (resumeResult != null) {
                result = resumeResult;
            } else {
                AgentExecutionContext context = new AgentExecutionContext(
                        sessionId,
                        WorkspaceContext.userId(),
                        WorkspaceContext.chatId(),
                        canonicalRole,
                        goal,
                        WorkspaceContext.autonomy(),
                        maxIterations
                );
                result = stateMachineRunner.run(context, engine, resumedHistory);
            }

            // --- CHECKPOINT FINAL STATE ---
            if (result instanceof AgentResult.AwaitingApproval) {
                log.debugf("Session %s paused, waiting for human approval.", sessionId);
            } else if (result != null && result.steps() != null && !result.steps().isEmpty()) {
                if (result instanceof AgentResult.Finished) {
                    agentStore.saveAndFinalize(sessionId, canonicalRole, result.steps(), CheckpointStatus.COMPLETED);
                } else if (result instanceof AgentResult.Failed f) {
                    var cp = agentStore.save(sessionId, canonicalRole, result.steps());
                    agentStore.markFailed(cp.getId(), f.reason());
                } else if (result instanceof AgentResult.LoopExceeded l) {
                    var cp = agentStore.save(sessionId, canonicalRole, result.steps());
                    agentStore.markFailed(cp.getId(), "Max iterations (" + l.maxIterations() + ") exceeded");
                }
            }

            return result;
        } catch (ComplianceViolationException cve) {
            // FAIL-CLOSED: mandatory audit write failed — agent MUST halt immediately.
            // No further LLM calls or tool executions permitted with a broken audit trail.
            log.errorf("[SECURITY ALERT] ComplianceViolationException for session=%s eventType=%s — halting agent.",
                    sessionId, cve.getEventType());
            result = AgentResult.failed("COMPLIANCE_VIOLATION: " + cve.getMessage(), AgentResult.FailureCategory.COMPLIANCE_VIOLATION, List.of());
            try {
                agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                    cp.setStatus(CheckpointStatus.COMPROMISED);
                    agentStore.save(cp);
                });
            } catch (Exception ignored) {}
            throw cve; // re-throw to physically halt the call stack
        } catch (Exception e) {
            result = AgentResult.failed("SYSTEM_ERROR: " + e.getMessage(), AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
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
        String role = WorkspaceContext.agentRole();
        if (role == null) {
            role = "coordinator";
        }
        AgentExecutionContext context = new AgentExecutionContext(
                WorkspaceScopeHolder.getRequired(),
                WorkspaceContext.userId(),
                WorkspaceContext.chatId(),
                role,
                goal,
                WorkspaceContext.autonomy(),
                maxIterations
        );
        return stateMachineRunner.run(context, engine, List.of());
    }



}