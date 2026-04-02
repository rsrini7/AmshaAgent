package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.reasoning.SystemPromptBuilder;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.audit.AuditHashUtil;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.structured.StructuredOutputException;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.util.MdcKeys;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

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

    @Inject
    AuditHashUtil hashUtil;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    AuditService auditService;

    @Inject
    AgentSseService agentSseService;

    /**
     * Execute the reasoning loop with default iteration limit (no lifecycle context).
     */
    @ActivateRequestContext
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
     *     {@link SystemPromptBuilder} detects the
     *     prefix and injects the skill's custom system prompt from
     *     {@link SkillRegistry}. The checkpoint row uses
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
            canonicalRole = AgentRoleEnum.isSkillRole(agentRole)
                    ? agentRole
                    : resolvedRole.toScopedName();
        } catch (IllegalArgumentException e) {
            log.warnf("[AgentLoop] Unknown agentRole '%s' — defaulting to COORDINATOR", agentRole);

            auditService.record(sessionId, WorkspaceContext.userId(), agentRole,
                SecurityEventType.INVALID_ROLE_FALLBACK,
                "Unknown agentRole '" + agentRole + "' — defaulted to COORDINATOR");

            Counter.builder("arcclaw.agent.role.unknown")
                .tag("role", agentRole)
                .register(meterRegistry)
                .increment();

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

        // FIX: Capture userId early into a local variable so the finally block can
        // pass it to observers without re-reading MDC (which may be unreliable in finally).
        // Initialized to "unknown" in case WorkspaceContext.userId() throws before assignment.
        // WorkspaceContext.userId() is already called unconditionally later in this method
        // (inside AgentExecutionContext construction), so this cannot introduce new failure modes.
        final String capturedUserId = WorkspaceContext.resolveUserId();

        try {
            // FIX: Store userId in MDC so it appears in structured log output alongside
            // SESSION_ID and AGENT_ROLE. Previously USER_ID was never set here,
            // meaning MDC.get(MdcKeys.USER_ID) in the finally block always returned null
            // and observers always received "unknown".
            MDC.put(MdcKeys.USER_ID, capturedUserId);

            // --- CHECKPOINT RESUME ---
            var existingCheckpoint = agentStore.load(sessionId, canonicalRole);
            if (existingCheckpoint.isPresent()) {
                var checkpoint = existingCheckpoint.get();
                CheckpointStatus status = checkpoint.getStatus();

                if (status == CheckpointStatus.IN_PROGRESS
                        || status == CheckpointStatus.AWAITING_APPROVAL) {
                    List<ReasoningStep> loadedSteps = agentStore.deserializeSteps(checkpoint);
                    if (loadedSteps != null) {
                        resumedHistory.addAll(loadedSteps);
                    }
                    log.infof("Resumed %d steps from active checkpoint (status=%s)",
                              resumedHistory.size(), status);
                } else {
                    // FIX: Archive the terminal checkpoint so findBySessionAndRole() (ORDER BY id DESC)
                    // and findLatestAwaitingApproval() never return this stale row in future calls.
                    // Without this, a COMPLETED row for (sessionId, researcher) at checkpoint_id=5
                    // stays in the DB and shadows a newer AWAITING_APPROVAL row at checkpoint_id=6,
                    // causing "/approve → No pending tool executions require approval".
                    log.debugf("Ignoring terminal checkpoint (status=%s). Archiving and starting fresh turn.",
                            status);
                    checkpoint.setStatus(CheckpointStatus.ARCHIVED);
                    agentStore.save(checkpoint);
                }
            }

            AgentExecutionContext agentExecutionContext = new AgentExecutionContext(
                    sessionId,
                    capturedUserId,  // reuse already-captured value
                    WorkspaceContext.chatId(),
                    canonicalRole,
                    goal,
                    WorkspaceContext.autonomy(),
                    maxIterations
            );

            AgentResult resumeResult = checkpointResumeHandler.handle(agentExecutionContext, resumedHistory, context);
            if (resumeResult != null) { 
                result = resumeResult;
            } else {
                // Fast-path: if delegate_to_agent just executed on resume, the sub-agent's
                // answer is already in the last history step. Skip the wrap-up LLM call entirely.
                // Guard: ⏳ prefix = sub-agent hit rate-limit — let state machine retry in that case.
                result = tryDelegateFinishFastPath(sessionId, resumedHistory)
                        .orElseGet(() -> stateMachineRunner.run(
                                agentExecutionContext, engine, resumedHistory));
            }

            if (result instanceof AgentResult.AwaitingApproval awaiting) {
                log.debugf("Session %s paused, waiting for human approval — tool=%s",
                        sessionId, awaiting.toolName());
                tryStampChannelInfo(sessionId, canonicalRole, context);
            } else if (result != null && result.steps() != null && !result.steps().isEmpty()) {
                if (result instanceof AgentResult.Finished) {
                    agentStore.saveAndFinalize(sessionId, canonicalRole,
                        result.steps(), CheckpointStatus.COMPLETED);
                    agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                        sessionId, canonicalRole, "COMPLETED", Instant.now()
                    ));
                } else if (result instanceof AgentResult.Failed f) {
                    var cp = agentStore.save(sessionId, canonicalRole, result.steps());
                    agentStore.markFailed(cp.getId(), f.reason());
                    agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                        sessionId, canonicalRole, "FAILED", Instant.now()
                    ));
                } else if (result instanceof AgentResult.LoopExceeded l) {
                    var cp = agentStore.save(sessionId, canonicalRole, result.steps());
                    agentStore.markFailed(cp.getId(),
                        "Max iterations (" + l.maxIterations() + ") exceeded");
                    agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                        sessionId, canonicalRole, "FAILED", Instant.now()
                    ));
                }
            }

            return result;

        } catch (StructuredOutputException e) {
            log.errorf("Structured output failure for session %s: %s", sessionId, e.getMessage());
            result = AgentResult.failed(e.getMessage(),
                    AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(resumedHistory));
            return result;

        } catch (BudgetExceededException | BudgetWarningException e) {
            // Re-throw so StructuredRetryLoop or higher-level components correctly
            // see the hard stop signal.
            throw e;

        } catch (ComplianceViolationException cve) {
            log.errorf("[SECURITY ALERT] ComplianceViolationException for session=%s eventType=%s " +
                       "— halting agent.", sessionId, cve.getEventType());
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
            // FIX: null-guard e.getMessage() — NullPointerException and several other JVM
            // exception types return null from getMessage(), which would produce
            // "SYSTEM_ERROR: null" in both the AgentResult and the checkpoint failure record.
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.errorf("Agent execution failed for session %s: %s", sessionId, errMsg, e);
            result = AgentResult.failed("SYSTEM_ERROR: " + errMsg,
                    AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
            try {
                agentStore.load(sessionId, canonicalRole).ifPresent(cp ->
                    agentStore.markFailed(cp.getId(), "SYSTEM_ERROR: " + errMsg)
                );
            } catch (Exception ignored) {}
            return result;

        } finally {
            if (result != null) {
                String goalHash = hashUtil.sha256hex(goal);
                final AgentResult finalResult = result;
                // FIX: use capturedUserId (set early in try block) instead of
                // MDC.get(MdcKeys.USER_ID) — cleaner and not subject to MDC lifecycle.
                observers.forEach(o -> o.onLoopComplete(
                    sessionId, capturedUserId, canonicalRole, goalHash, finalResult));
            }
            // --- CONTEXT TEARDOWN ---
            MDC.remove(MdcKeys.SESSION_ID);
            MDC.remove(MdcKeys.AGENT_ROLE);
            MDC.remove(MdcKeys.USER_ID); // FIX: was missing — leaked into next request on same thread
            otelContext.endSessionSpan(sessionId);
        }
    }

    /**
     * After CheckpointResumeHandler executes a pending tool on resume, checks if
     * the tool was delegate_to_agent with a clean result. If so, returns the
     * sub-agent's answer directly — skipping the coordinator wrap-up LLM call
     * (~9,800 tokens) and the re-delegation loop.
     *
     * Returns empty() for all other tools, forcing the normal state machine path.
     */
    private Optional<AgentResult> tryDelegateFinishFastPath(
            String sessionId, List<ReasoningStep> resumedHistory) {

        if (resumedHistory.isEmpty()) return Optional.empty();

        ReasoningStep last = resumedHistory.getLast();
        String toolName = last.action();
        String observation = last.observation();

        if (!ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)) return Optional.empty();
        if (observation == null || observation.isBlank()) return Optional.empty();

        // FIX: also guard against tool failure messages that don't start with ⏳/⚠️.
        // e.g. "Parameter 'agent' is required." — success=false but no sentinel prefix.
        // Without this guard, tool errors are sent to Telegram as finished answers.
        if (observation.startsWith("⏳")
                || observation.startsWith("⚠️")
                || observation.startsWith("Tool execution failed")
                || observation.startsWith("Parameter ")        // ← tool param validation errors
                || observation.startsWith("❌")) {             // ← explicit failure prefix
            return Optional.empty();
        }

        log.infof("[AgentLoop] Fast-path: delegate_to_agent resume result used as FINISH " +
                "for session %s — skipping coordinator wrap-up LLM call", sessionId);
        return Optional.of(AgentResult.finished(observation, List.copyOf(resumedHistory)));
    }

    private void tryStampChannelInfo(String sessionId, String canonicalRole,
                                     MessageContext context) {
        if (context == null || context.getUser() == null) {
            log.warnf("No MessageContext user for session %s — " +
                      "approval timeout notification will be skipped.", sessionId);
            return;
        }
        try {
            agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                cp.setChannelPlatformId(context.getUser().platformId());
                cp.setChannelType(context.getUser().channel().name());
                agentStore.save(cp);
            });
            log.debugf("Stamped channel info on checkpoint session=%s channel=%s platformId=%s",
                       sessionId, context.getUser().channel(), context.getUser().platformId());
        } catch (Exception e) {
            log.warnf("Failed to stamp channel info for session %s — " +
                      "timeout notification may be skipped. Cause: %s",
                      sessionId, e.getMessage());
            // Never re-throw — channel stamping must not corrupt the AwaitingApproval result.
        }
    }

    // Keep the two-arg overload for callers that don't need session lifecycle.
    @ActivateRequestContext
    public AgentResult run(String goal, ReasoningEngine engine, int maxIterations) {
        String sessionId = WorkspaceScopeHolder.getIfActive();
        return run(sessionId, AgentRoleEnum.COORDINATOR.toScopedName(),
                   goal, engine, maxIterations);
    }
}
