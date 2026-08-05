package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.amsha.channel.api.ChannelPlugin;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.AgentErrorMessageUtil;

import org.jboss.logging.Logger;

/**
 * Manages checkpoint loading and resume decision branching for the
 * Amsha Agent reasoning loop.
 *
 * <p>Extracted from {@code AgentLoop.runWithRole} as part of
 * God-class decomposition. Owns three of the seven original responsibilities:
 * <ul>
 *   <li><strong>Checkpoint load logic</strong> - retrieves the latest
 *       checkpoint for the (session, role) key from the store.</li>
 *   <li><strong>Resume decision branching</strong> - evaluates whether to
 *       resume an {@code IN_PROGRESS} run, continue an approved
 *       {@code AWAITING_APPROVAL} turn, or discard stale approvals.</li>
 *   <li><strong>Stale approval archiving</strong> - atomically archives
 *       checkpoints that were abandoned by the user in favor of a new goal,
 *       preventing history contamination.</li>
 * </ul>
 *
 * <h3>🚨 Transaction-free zone (§5.9)</h3>
 * <p>No method in this class may carry {@code @Transactional} or
 * {@code @Transactional(REQUIRED)}. The stale discard path calls
 * {@code agentStore.save(checkpoint)} which internally uses
 * {@code REQUIRES_NEW} in the SQLite adapter - that is safe. An outer
 * {@code @Transactional(REQUIRED)} here would reintroduce the P84
 * SQLITE_BUSY cascade.
 *
 * <h3>🚨 Double-resume guard (§3.4)</h3>
 * <p>The {@code finally} block that calls
 * {@code agentStore.clearBatchResuming(sessionId)} remains in
 * {@link CheckpointResumeHandler} - it is NOT moved here. This coordinator
 * is only for the pre-loop checkpoint load/branch logic.
 *
 * <h3>🚨 Denial path (§3.4)</h3>
 * <p>This coordinator must never route a denied approval into the main agent
 * loop. Denial is a termination path handled by {@link CheckpointResumeHandler}.
 */
@ApplicationScoped
public class CheckpointResumeCoordinator {

    private static final Logger log = Logger.getLogger(CheckpointResumeCoordinator.class);

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    AuditService auditService;

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Inject
    ChatMemoryWriter chatMemoryWriter;

    /**
     * Decision outcome for the checkpoint load phase.
     */
    public sealed interface ResumeDecision {
        /** Start a completely fresh goal. */
        record FreshStart() implements ResumeDecision {}

        /** Resume with existing history. */
        record ResumedHistory(
                List<ReasoningStep> history, int iterationsUsed, String checkpointRole, String contextDataJson)
                implements ResumeDecision {}

        /** Stale approval was active but discarded; starting fresh. */
        record AbortedStale() implements ResumeDecision {}
    }

    /**
     * Loads the latest checkpoint for the session and resolves the resume
     * strategy.
     *
     * <p><strong>Branching rules (§3.4):</strong>
     * <ul>
     *   <li>{@code IN_PROGRESS} → Always restored verbatim.</li>
     *   <li>{@code AWAITING_APPROVAL} + {@code isResumeSignal} → Restored so
     *       {@code CheckpointResumeHandler} can execute the tool.</li>
     *   <li>{@code AWAITING_APPROVAL} + new message → Archived as stale;
     *       user notified; history discarded (Fresh Start).</li>
     *   <li>Terminal status (COMPLETED/FAILED) → Archived to avoid shadowing
     *       future rows; history discarded.</li>
     * </ul>
     *
     * @param sessionId workspace session identifier
     * @param canonicalRole canonical agent role
     * @param capturedUserId userId captured at loop entry (§2.3/§7.29)
     * @param context incoming message context for resume-signal detection
     * @return one of the three {@link ResumeDecision} variants
     */
    public ResumeDecision resolveResumePath(
            String sessionId, String canonicalRole, String capturedUserId, MessageContext context) {

        Optional<AgentCheckpoint> existingCheckpoint = agentStore.findLatestNonArchivedCheckpoint(sessionId);
        if (existingCheckpoint.isEmpty()) {
            return new ResumeDecision.FreshStart();
        }

        AgentCheckpoint checkpoint = existingCheckpoint.get();
        CheckpointStatus status = checkpoint.getStatus();

        // Ensure we only resume if the role matches, OR if it's a cross-role resume (§7.31/§7.46)
        boolean roleMatches = Objects.equals(checkpoint.getAgentRole(), canonicalRole);
        boolean isResumableStatus =
                (status == CheckpointStatus.AWAITING_APPROVAL || status == CheckpointStatus.RESUMING);

        if (!roleMatches && !isResumableStatus) {
            log.debugf(
                    "CheckpointResumeCoordinator: latest checkpoint role (%s) != current role (%s). Starting fresh.",
                    checkpoint.getAgentRole(), canonicalRole);
            return new ResumeDecision.FreshStart();
        }

        if (status == CheckpointStatus.IN_PROGRESS) {
            return handleInProgress(checkpoint);
        }

        if (status == CheckpointStatus.AWAITING_APPROVAL || status == CheckpointStatus.RESUMING) {
            return handleAwaitingApproval(checkpoint, context, capturedUserId, canonicalRole);
        }

        if (status == CheckpointStatus.COMPLETED && roleMatches) {
            return handleCompleted(checkpoint);
        }

        return handleTerminalMismatch(checkpoint, status, roleMatches);
    }

    private ResumeDecision handleInProgress(AgentCheckpoint checkpoint) {
        List<ReasoningStep> loadedSteps = agentStore.deserializeSteps(checkpoint);
        log.infof(
                "CheckpointResumeCoordinator: restored %d steps from IN_PROGRESS for session=%s",
                loadedSteps != null ? loadedSteps.size() : 0, checkpoint.getSessionId());
        return new ResumeDecision.ResumedHistory(
                loadedSteps != null ? loadedSteps : new ArrayList<>(),
                checkpoint.getIterationsUsed(),
                checkpoint.getAgentRole(),
                checkpoint.getContextDataJson());
    }

    private ResumeDecision handleAwaitingApproval(
            AgentCheckpoint checkpoint, MessageContext context, String capturedUserId, String canonicalRole) {
        CheckpointStatus status = checkpoint.getStatus();
        boolean isResume = (context != null && context.isResumeSignal()) || status == CheckpointStatus.RESUMING;
        if (isResume) {
            List<ReasoningStep> loadedSteps = agentStore.deserializeSteps(checkpoint);
            log.infof(
                    "CheckpointResumeCoordinator: restored %d steps from %s for session=%s",
                    loadedSteps != null ? loadedSteps.size() : 0, status, checkpoint.getSessionId());
            return new ResumeDecision.ResumedHistory(
                    loadedSteps != null ? loadedSteps : new ArrayList<>(),
                    checkpoint.getIterationsUsed(),
                    checkpoint.getAgentRole(),
                    checkpoint.getContextDataJson());
        } else {
            archiveStaleApproval(checkpoint.getSessionId(), canonicalRole, capturedUserId, checkpoint, context);
            return new ResumeDecision.AbortedStale();
        }
    }

    private ResumeDecision handleCompleted(AgentCheckpoint checkpoint) {
        List<ReasoningStep> loadedSteps = agentStore.deserializeSteps(checkpoint);
        log.infof(
                "CheckpointResumeCoordinator: restored %d steps from COMPLETED turn for session=%s",
                loadedSteps != null ? loadedSteps.size() : 0, checkpoint.getSessionId());

        checkpoint.setStatus(CheckpointStatus.ARCHIVED);
        agentStore.save(checkpoint);

        int MAX_CARRY_STEPS = 10;
        List<ReasoningStep> pruned = (loadedSteps != null && loadedSteps.size() > MAX_CARRY_STEPS)
                ? loadedSteps.subList(loadedSteps.size() - MAX_CARRY_STEPS, loadedSteps.size())
                : (loadedSteps != null ? loadedSteps : new ArrayList<>());

        return new ResumeDecision.ResumedHistory(pruned, 0, checkpoint.getAgentRole(), checkpoint.getContextDataJson());
    }

    private ResumeDecision handleTerminalMismatch(
            AgentCheckpoint checkpoint, CheckpointStatus status, boolean roleMatches) {
        log.debugf(
                "CheckpointResumeCoordinator: ignoring terminal checkpoint (status=%s, roleMatch=%b). Archiving.",
                status, roleMatches);
        checkpoint.setStatus(CheckpointStatus.ARCHIVED);
        agentStore.save(checkpoint);
        return new ResumeDecision.FreshStart();
    }

    public static final String FAIL_REASON_STALE_DISCARD =
            "Discarded: new user message received before approval was given.";

    private void archiveStaleApproval(
            String sessionId,
            String _canonicalRole,
            String capturedUserId,
            AgentCheckpoint checkpoint,
            MessageContext context) {
        String staleTool = checkpoint.getPendingToolName();
        log.warnf(
                "CheckpointResumeCoordinator: STALE-APPROVAL session=%s had pending tool '%s' "
                        + "but received new message. Archiving and starting fresh.",
                sessionId, staleTool);

        // 🟢 FIX Bug 3: Scrub unfulfilled tool calls from ChatMemoryStore.
        // This ensures the discarded tool call doesn't re-surface in the next turn's history
        // when the LLM sees the previous AiMessage with tool requests but no results.
        chatMemoryWriter.scrubUnfulfilledToolCalls(sessionId);

        checkpoint.setStatus(CheckpointStatus.ARCHIVED);
        checkpoint.setFailReason(FAIL_REASON_STALE_DISCARD);
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        agentStore.save(checkpoint);

        // Notify user best-effort
        if (context != null && context.getUser() != null) {
            notifyStaleApprovalDiscarded(sessionId, staleTool, context);
        }

        auditService.record(
                sessionId,
                capturedUserId,
                checkpoint.getAgentRole(),
                SecurityEventType.CHECKPOINT_FAILED,
                "Stale AWAITING_APPROVAL discarded - new message received. tool=" + staleTool);
    }

    private void notifyStaleApprovalDiscarded(String sessionId, String staleTool, MessageContext context) {
        try {
            String toolLabel = staleTool != null ? staleTool : "a pending tool";
            String notice = """
                    ⚠️ A previous approval request for `%s` was discarded because you sent a new message.
                    Starting fresh with your new goal.
                    Your previous task was cancelled. Reply /retry to run it again.
                    """.formatted(toolLabel);

            channelPluginRegistry
                    .find(context.getUser().channel().id())
                    .map(ChannelPlugin::sender)
                    .ifPresent(sender -> sender.sendRaw(context.getUser(), notice));
        } catch (Exception e) {
            log.warnf(
                    "CheckpointResumeCoordinator.notifyStaleApprovalDiscarded: failed for session %s: %s",
                    sessionId, AgentErrorMessageUtil.safeMessage(e));
        }
    }
}
