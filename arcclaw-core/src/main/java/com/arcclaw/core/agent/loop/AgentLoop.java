package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;

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
import com.arcclaw.core.structured.StructuredOutputException;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.util.MdcKeys;

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
    private static final String SKILL_PREFIX = "skill:";

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

    @Inject
    AuditHashUtil hashUtil;

    /**
     * Execute the reasoning loop with default iteration limit (no lifecycle context).
     */
    public AgentResult run(String goal, ReasoningEngine engine) {
        return run(goal, engine, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Lifecycle-aware entry point — sets up context, resumes from checkpoint,
     * and tears down on completion.
     *
     * <p>Role resolution rules:
     * <ul>
     * <li>Standard roles (e.g. "coordinator", "researcher") resolve via
     *     {@link AgentRoleEnum#fromString} and use the canonical name for
     *     checkpoint storage, MDC, and system prompt building.</li>
     * <li>Skill roles (e.g. "skill:code-reviewer") preserve the full
     *     {@code skill:X} string as {@code canonicalRole}. This is intentional:
     *     {@link com.arcclaw.core.agent.reasoning.SystemPromptBuilder} detects the
     *     prefix and injects the skill's custom system prompt from
     *     {@link com.arcclaw.core.skill.SkillRegistry}. The checkpoint row uses
     *     the full {@code skill:X} key, giving each skill its own independent
     *     checkpoint and conversation history — correct and desired.</li>
     * </ul>
     *
     * @param sessionId     The workspace session ID.
     * @param agentRole     The agent role identifier (e.g. "coordinator" or "skill:code-reviewer").
     * @param goal          The user's goal / task description.
     * @param engine        The reasoning engine (LLM abstraction).
     * @param maxIterations Maximum loop iterations before forced termination.
     * @return The final result with reasoning trace.
     */
    @ActivateRequestContext
    public AgentResult run(String sessionId, String agentRole, String goal,
            ReasoningEngine engine, int maxIterations) {
        return run(sessionId, agentRole, goal, engine, maxIterations, null);
    }

    @ActivateRequestContext
    public AgentResult run(String sessionId, String agentRole, String goal,
            ReasoningEngine engine, int maxIterations, MessageContext context) {

        // --- Resolve agentRole at the API boundary ---
        //
        // For skill roles (e.g. "skill:code-reviewer"):
        //   - AgentRoleEnum.fromString() returns RESEARCHER (no IAE) — correct tool set.
        //   - BUT we MUST NOT call resolvedRole.toScopedName() ("researcher") as the
        //     canonicalRole — that would strip the "skill:" prefix and prevent
        //     SystemPromptBuilder from injecting the skill's custom system prompt.
        //   - Solution: preserve the original agentRole string as canonicalRole.
        //
        // For standard roles (e.g. "coordinator", "researcher"):
        //   - Behaves exactly as before: resolve enum, use toScopedName().
        //
        final String canonicalRole;
        try {
            AgentRoleEnum resolvedRole = AgentRoleEnum.fromString(agentRole);
            // Preserve skill: prefix so SystemPromptBuilder sees it intact.
            // For standard roles, normalize via toScopedName() as before.
            canonicalRole = (agentRole != null && agentRole.startsWith(SKILL_PREFIX))
                    ? agentRole
                    : resolvedRole.toScopedName();
        } catch (IllegalArgumentException e) {
            log.warnf("[AgentLoop] Unknown agentRole '%s' — defaulting to COORDINATOR", agentRole);
            // Fall through with coordinator as safe default
            return runWithRole(sessionId, AgentRoleEnum.COORDINATOR.toScopedName(),
                               goal, engine, maxIterations, context);
        }


        return runWithRole(sessionId, canonicalRole, goal, engine, maxIterations, context);
    }


    /**
     * Internal execution path — called after role resolution is complete.
     * Extracted to avoid duplicating the try/finally lifecycle block.
     * Package-private (not private) so @ActivateRequestContext can be proxied by Quarkus ARC.
     */
    @ActivateRequestContext
    AgentResult runWithRole(String sessionId, String canonicalRole, String goal,
            ReasoningEngine engine, int maxIterations, MessageContext context) {

        // --- CONTEXT SETUP ---
        MDC.put(MdcKeys.SESSION_ID, sessionId);
        MDC.put(MdcKeys.AGENT_ROLE, canonicalRole);

        AgentResult result = null;
        List<ReasoningStep> resumedHistory = new ArrayList<>();
        try {
            // --- CHECKPOINT RESUME ---
            var existingCheckpoint = agentStore.load(sessionId, canonicalRole);
            if (existingCheckpoint.isPresent()) {
                var checkpoint = existingCheckpoint.get();
                CheckpointStatus status = checkpoint.getStatus();

                if (status == CheckpointStatus.IN_PROGRESS || status == CheckpointStatus.AWAITING_APPROVAL) {
                    List<ReasoningStep> loadedSteps = agentStore.deserializeSteps(checkpoint);
                    if (loadedSteps != null) {
                        resumedHistory.addAll(loadedSteps);
                    }
                    log.infof("Resumed %d steps from active checkpoint (status=%s)", resumedHistory.size(), status);
                } else {
                    log.debugf("Ignoring terminal checkpoint (status=%s). Starting fresh turn.", status);
                }
            }

            AgentResult resumeResult = checkpointResumeHandler.handle(
                    sessionId, canonicalRole, goal, resumedHistory, context);
            if (resumeResult != null) {
                result = resumeResult;
            } else {
                AgentExecutionContext agentExecutionContext = new AgentExecutionContext(
                        sessionId,
                        WorkspaceContext.userId(),
                        WorkspaceContext.chatId(),
                        canonicalRole,
                        goal,
                        WorkspaceContext.autonomy(),
                        maxIterations
                );
                result = stateMachineRunner.run(agentExecutionContext, engine, resumedHistory);
            }

            // --- CHECKPOINT FINAL STATE ---
            if (result instanceof AgentResult.AwaitingApproval awaiting) {
                log.debugf("Session %s paused, waiting for human approval — tool=%s",
                        sessionId, awaiting.toolName());

                if (context != null && context.getUser() != null) {
                    agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                        cp.setChannelPlatformId(context.getUser().platformId());
                        cp.setChannelType(context.getUser().channel().name());
                        agentStore.save(cp);
                        log.debugf("Stamped channel info on checkpoint session=%s channel=%s platformId=%s",
                                sessionId, context.getUser().channel(), context.getUser().platformId());
                    });
                } else {
                    log.warnf("No MessageContext user for session=%s — approval timeout notification will be skipped",
                            sessionId);
                }
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
        } catch (StructuredOutputException e) {
            log.errorf("Structured output failure for session %s: %s", sessionId, e.getMessage());
            result = AgentResult.failed(e.getMessage(), 
                    AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(resumedHistory));
            return result;
        } catch (com.arcclaw.core.governance.BudgetExceededException 
                 | com.arcclaw.core.governance.BudgetWarningException e) {
            // Re-throw so StructuredRetryLoop or higher-level components correctly
            // see the hard stop signal.
            throw e;
        } catch (ComplianceViolationException cve) {
            log.errorf("[SECURITY ALERT] ComplianceViolationException for session=%s eventType=%s — halting agent.",
                    sessionId, cve.getEventType());
            result = AgentResult.failed("COMPLIANCE_VIOLATION: " + cve.getMessage(),
                    AgentResult.FailureCategory.COMPLIANCE_VIOLATION, List.of());
            try {
                agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                    cp.setStatus(CheckpointStatus.COMPROMISED);
                    agentStore.save(cp);
                });
            } catch (Exception ignored) {}
            return result;
        } catch (Exception e) {
            log.errorf("Agent execution failed for session %s: %s", sessionId, e.getMessage(), e);
            result = AgentResult.failed("SYSTEM_ERROR: " + e.getMessage(),
                    AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
            try {
                agentStore.load(sessionId, canonicalRole).ifPresent(cp ->
                    agentStore.markFailed(cp.getId(), "SYSTEM_ERROR: " + e.getMessage())
                );
            } catch (Exception ignored) {}
            return result;
        } finally {
            if (result != null) {
                String goalHash = hashUtil.sha256hex(goal);
                String userId = MDC.get(MdcKeys.USER_ID) != null ? (String) MDC.get(MdcKeys.USER_ID) : "unknown";
                final AgentResult finalResult = result;
                observers.forEach(o -> o.onLoopComplete(sessionId, userId, canonicalRole, goalHash, finalResult));
            }
            // --- CONTEXT TEARDOWN ---
            MDC.remove(MdcKeys.SESSION_ID);
            MDC.remove(MdcKeys.AGENT_ROLE);
            otelContext.endSessionSpan(sessionId);
        }
    }

    // Keep the two-arg overload for callers that don't need session lifecycle.
    public AgentResult run(String goal, ReasoningEngine engine, int maxIterations) {
        String sessionId = WorkspaceScopeHolder.getIfActive();
        return run(sessionId, AgentRoleEnum.COORDINATOR.toScopedName(), goal, engine, maxIterations);
    }
}