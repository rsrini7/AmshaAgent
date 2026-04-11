package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelPlugin;
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
import com.arcclaw.core.channel.ChannelPluginRegistry;
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
import com.arcclaw.core.util.AgentErrorMessageUtil;
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
    ChannelPluginRegistry channelPluginRegistry;

    @Inject
    AuditHashUtil hashUtil;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    AuditService auditService;

    @Inject
    AgentSseService agentSseService;

    @Inject
    ReasoningEngine reasoningEngine;

    /**
     * Entry point for resuming a rate-limited or approval-paused session.
     * Used by the RateLimitRetryScheduler. §7.36
     */
    @ActivateRequestContext
    public AgentResult resume(String sessionId, String agentRole, String goal) {
        return run(sessionId, agentRole, goal, reasoningEngine, DEFAULT_MAX_ITERATIONS);
    }

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
        // Ensure storage goals have enough budget (min 3 steps)
        int budget = maxIterations;
        if (goal != null && (goal.toLowerCase().contains("store") || goal.toLowerCase().contains("remember"))) {
            budget = Math.max(budget, 3);
        }
        return run(sessionId, agentRole, goal, null, engine, budget, null);
    }

    @ActivateRequestContext
    public AgentResult run(String sessionId, String agentRole, String goal,
            ReasoningEngine engine, int maxIterations, MessageContext context) {
        return run(sessionId, agentRole, goal, null, engine, maxIterations, context);
    }

    @ActivateRequestContext
    public AgentResult run(String sessionId, String agentRole, String goal, String systemNote,
            ReasoningEngine engine, int maxIterations, MessageContext context) {

        // --- Resolve agentRole at the API boundary ---
        final String canonicalRole;
        try {
            AgentRoleEnum resolvedRole = AgentRoleEnum.fromString(agentRole);
            canonicalRole = AgentRoleEnum.isSkillRole(agentRole)
                    ? agentRole
                    : resolvedRole.toScopedName();
        } catch (IllegalArgumentException e) {
            log.warnf("[AgentLoop] Unknown agentRole '%s' — defaulting to COORDINATOR", agentRole);
 
            auditService.record(sessionId, WorkspaceContext.requireUserId(), agentRole,
                SecurityEventType.INVALID_ROLE_FALLBACK,
                "Unknown agentRole '" + agentRole + "' — defaulted to COORDINATOR");

            Counter.builder("arcclaw.agent.role.unknown")
                .tag("role", agentRole)
                .register(meterRegistry)
                .increment();

            return runWithRole(sessionId, AgentRoleEnum.COORDINATOR.toScopedName(),
                               goal, systemNote, engine, maxIterations, context);
        }

        return runWithRole(sessionId, canonicalRole, goal, systemNote, engine, maxIterations, context);
    }

    /**
     * Internal execution path — called after role resolution is complete.
     * Extracted to avoid duplicating the try/finally lifecycle block.
     * Package-private (not private) so @ActivateRequestContext can be proxied by Quarkus ARC.
     */
    @ActivateRequestContext
    AgentResult runWithRole(String sessionId, String canonicalRole, String goal, String systemNote,
            ReasoningEngine engine, int maxIterations, MessageContext context) {

        // --- CONTEXT SETUP ---
        MDC.put(MdcKeys.SESSION_ID, sessionId);
        MDC.put(MdcKeys.AGENT_ROLE, canonicalRole);

        AgentResult result = null;
        List<ReasoningStep> resumedHistory = new ArrayList<>();

        // Capture userId early into a local variable so the finally block can
        // pass it to observers without re-reading MDC (which may be unreliable in finally).
        // Initialized to "unknown" in case WorkspaceContext.userId() throws before assignment.
        // WorkspaceContext.userId() is already called unconditionally later in this method
        // (inside AgentExecutionContext construction), so this cannot introduce new failure modes.
        final String capturedUserId = WorkspaceContext.resolveUserId();

        try {
            // Store userId in MDC so it appears in structured log output alongside
            // SESSION_ID and AGENT_ROLE. Previously USER_ID was never set here,
            // meaning MDC.get(MdcKeys.USER_ID) in the finally block always returned null
            // and observers always received "unknown".
            MDC.put(MdcKeys.USER_ID, capturedUserId);

            // CHECKPOINT RESUME
            var existingCheckpoint = agentStore.load(sessionId, canonicalRole);
            if (existingCheckpoint.isPresent()) {
                var checkpoint = existingCheckpoint.get();
                CheckpointStatus status = checkpoint.getStatus();

                if (status == CheckpointStatus.IN_PROGRESS) {
                    // ── Normal mid-run resume: restore history and continue ──────────────
                    List<ReasoningStep> loadedSteps = agentStore.deserializeSteps(checkpoint);
                    if (loadedSteps != null) resumedHistory.addAll(loadedSteps);
                    log.infof("Resumed %d steps from IN_PROGRESS checkpoint for session=%s",
                            resumedHistory.size(), sessionId);

                } else if (status == CheckpointStatus.AWAITING_APPROVAL) {
                    // ── HITL pause detected — behaviour depends on whether this is a
                    //    resume signal (approve/deny) or a brand-new user message ─────────
                    boolean isResume = context != null && context.isResumeSignal();

                    if (isResume) {
                        // Legitimate resume: restore history so CheckpointResumeHandler
                        // can re-execute the approved/denied tool.
                        List<ReasoningStep> loadedSteps = agentStore.deserializeSteps(checkpoint);
                        if (loadedSteps != null) resumedHistory.addAll(loadedSteps);
                        log.infof("Resumed %d steps from AWAITING_APPROVAL checkpoint for session=%s (resume signal)",
                                resumedHistory.size(), sessionId);

                    } else {
                        // ── new message arrived while a stale approval is pending.
                        //    This happens after a JVM restart (SessionReaper leaves the
                        //    checkpoint alive for SSE replay) or when the user simply
                        //    sends a new goal without approving the previous tool.
                        //
                        //    Correct behaviour:
                        //      1. Archive the stale checkpoint so it can never shadow a
                        //         future AWAITING_APPROVAL row (same session/role key).
                        //      2. Clear pendingTool fields so no ghost tool lingers.
                        //      3. Do NOT restore history — start this goal completely fresh.
                        //      4. Notify the user so they know what was discarded.
                        // ─────────────────────────────────────────────────────────────────
                        String staleTool = checkpoint.getPendingToolName();
                        log.warnf("STALE-APPROVAL session=%s role=%s had pending tool '%s' "
                                + "but received a new message (not a resume signal). "
                                + "Archiving stale checkpoint and starting fresh.",
                                sessionId, canonicalRole, staleTool);

                        checkpoint.setStatus(CheckpointStatus.ARCHIVED);
                        checkpoint.setFailReason(
                                "Discarded: new user message received before approval was given.");
                        checkpoint.setPendingToolName(null);
                        checkpoint.setPendingToolParametersJson(null);
                        agentStore.save(checkpoint);

                        // Notify the user via their channel so they are not confused
                        // about the abandoned approval request.
                        if (context != null && context.getUser() != null) {
                            notifyStaleApprovalDiscarded(sessionId, canonicalRole,
                                    staleTool, context);
                        }

                        auditService.record(sessionId, capturedUserId, canonicalRole,
                                SecurityEventType.CHECKPOINT_FAILED,
                                "Stale AWAITING_APPROVAL discarded — new message received. tool=" + staleTool);

                        // resumedHistory stays empty → stateMachineRunner starts fresh ✅
                    }

                } else {
                    // ── Terminal status (COMPLETED, FAILED, ARCHIVED, COMPROMISED):
                    //    archive so it never shadows a new AWAITING_APPROVAL row.
                    log.debugf("Ignoring terminal checkpoint (status=%s). Archiving and starting fresh.",
                            status);
                    checkpoint.setStatus(CheckpointStatus.ARCHIVED);
                    agentStore.save(checkpoint);
                }
            }

            String channelType = context != null && context.getUser() != null ? context.getUser().channel().name() : null;
            String channelPlatformId = context != null && context.getUser() != null ? context.getUser().platformId() : null;

            AgentExecutionContext agentExecutionContext = new AgentExecutionContext(
                    sessionId,
                    capturedUserId,  // reuse already-captured value
                    WorkspaceContext.requireChatId(),
                    canonicalRole,
                    goal,
                    WorkspaceContext.requireAutonomyOrSupervised(),
                    maxIterations,
                    channelType,
                    channelPlatformId,
                    systemNote,
                    context != null ? context.getData() : null
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
            } else if (result instanceof AgentResult.AwaitingRetry awaitingRetry) {
                log.warnf("Session %s paused due to rate limit — retry after %dms",
                        sessionId, awaitingRetry.retryAfterMs());
                agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                    cp.setStatus(CheckpointStatus.AWAITING_RETRY);
                    cp.setRetryAfterMs(awaitingRetry.retryAfterMs());
                    agentStore.save(cp);

                    // GA P 4 — Notify Telegram users about the pause
                    if (context != null && context.getUser() != null && "TELEGRAM".equalsIgnoreCase(cp.getChannelType())) {
                        long waitSec = awaitingRetry.retryAfterMs() / 1000;
                        String notice = "⏳ Rate limit hit — I'll automatically retry in " + waitSec + " seconds.";
                        channelPluginRegistry.find(context.getUser().channel().id())
                                .map(ChannelPlugin::sender)
                                .ifPresent(sender -> sender.sendRaw(context.getUser(), notice));
                    }
                });
                agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                        sessionId, canonicalRole, "AWAITING_RETRY", Instant.now()
                ));
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
            String msg = AgentErrorMessageUtil.safeMessage(e);
            log.errorf("Structured output failure for session %s: %s", sessionId, msg);
            result = AgentResult.failed(msg,
                    AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(resumedHistory));
            return result;

        } catch (BudgetExceededException | BudgetWarningException e) {
            // Re-throw so StructuredRetryLoop or higher-level components correctly
            // see the hard stop signal.
            throw e;

        } catch (ComplianceViolationException cve) {
            log.errorf("[SECURITY ALERT] ComplianceViolationException for session=%s eventType=%s " +
                       "— halting agent.", sessionId, cve.getEventType());
            result = AgentResult.failed("COMPLIANCE_VIOLATION: " + AgentErrorMessageUtil.safeMessage(cve),
                    AgentResult.FailureCategory.COMPLIANCE_VIOLATION, List.of());
            try {
                agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                    cp.setStatus(CheckpointStatus.COMPROMISED);
                    agentStore.save(cp);
                });
            } catch (Exception ignored) {}
            return result;

        } catch (Exception e) {
            // null-guard e.getMessage() — NullPointerException and several other JVM
            // exception types return null from getMessage(), which would produce
            // "SYSTEM_ERROR: null" in both the AgentResult and the checkpoint failure record.
            String errMsg = AgentErrorMessageUtil.safeMessage(e);
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
                // use capturedUserId (set early in try block) instead of
                // MDC.get(MdcKeys.USER_ID) — cleaner and not subject to MDC lifecycle.
                observers.forEach(o -> o.onLoopComplete(
                    sessionId, capturedUserId, canonicalRole, goalHash, finalResult));
            }
            // --- CONTEXT TEARDOWN ---
            MDC.remove(MdcKeys.SESSION_ID);
            MDC.remove(MdcKeys.AGENT_ROLE);
            MDC.remove(MdcKeys.USER_ID); // was missing — leaked into next request on same thread
            otelContext.endSessionSpan(sessionId);
        }
    }

    /**
     * Sends a one-line channel notification when a stale AWAITING_APPROVAL
     * checkpoint is discarded because the user sent a new message instead of
     * approving or denying.
     *
     * <p>Best-effort — never throws. A failure here must not affect the
     * main agent execution path.
     */
    private void notifyStaleApprovalDiscarded(String sessionId,
                                            String canonicalRole,
                                            String staleTool,
                                            MessageContext context) {
        if (context == null || context.getUser() == null) return;
        try {
            String toolLabel = staleTool != null ? "`" + staleTool + "`" : "a pending tool";
            String notice = "⚠️ A previous approval request for " + toolLabel
                    + " was discarded because you sent a new message. "
                    + "Starting fresh with your new goal.";

            channelPluginRegistry
                    .find(context.getUser().channel().id())
                    .map(ChannelPlugin::sender)
                    .ifPresent(sender -> sender.sendRaw(context.getUser(), notice));
        } catch (Exception e) {
            log.warnf("notifyStaleApprovalDiscarded: could not notify user for session=%s — %s",
                    sessionId, AgentErrorMessageUtil.safeMessage(e));
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

        // B1 (F9): Use structural success flag if available.
        // If the tool failed, we must not fast-path the "answer" as it's likely an error message.
        if (last.success() != null) {
            if (!last.success()) {
                log.warnf("[AgentLoop] Delegate tool for session %s marked as failed — skipping fast-path.", sessionId);
                return Optional.empty();
            }
        } else {
            // Legacy/fallback: if success is null (older history), use previous string guards.
            // B1 (F9) also notes we should avoid coupling logic to English strings.
            if (observation.startsWith("⏳")
                    || observation.startsWith("⚠️")
                    || observation.startsWith("Tool execution failed")
                    || observation.startsWith("Parameter ")
                    || observation.startsWith("❌")) {
                return Optional.empty();
            }
        }

        // F3: FAILURE PHASE QUALITY GATE
        // If the sub-agent result contains phrases indicating failure or inability to perform,
        // do NOT fast-path it. Let the coordinator see the failure and decide on a fallback
        // (e.g. retry with different params, try a different tool).
        // F3 UPDATE: Only check the first 150 characters to reduce false positives on valid answers.
        String lowerObs = observation.toLowerCase();
        String opening = lowerObs.length() > 150 ? lowerObs.substring(0, 150) : lowerObs;
        if (opening.contains("i was unable") || opening.contains("unable to fetch") 
                || opening.contains("could not find") || opening.contains("i cannot") 
                || opening.contains("could not retrieve")) {
            log.infof("[AgentLoop] Delegate result for session %s contains failure phrases in opening — skipping fast-path to allow coordinator fallback", sessionId);
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
                      sessionId, AgentErrorMessageUtil.safeMessage(e));
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
