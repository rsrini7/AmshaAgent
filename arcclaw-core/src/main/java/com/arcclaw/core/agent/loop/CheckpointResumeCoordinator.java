package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import org.jboss.logging.Logger;

/**
 * Manages checkpoint loading and resume decision branching for the
 * ArcClaw reasoning loop.
 *
 * <p>Extracted from {@code AgentLoop.runWithRole} as part of Phase 2
 * God-class decomposition. Owns three of the seven original responsibilities:
 * <ul>
 *   <li><strong>Checkpoint load logic</strong> — retrieves the latest
 *       checkpoint for the (session, role) key from the store.</li>
 *   <li><strong>Resume decision branching</strong> — evaluates whether to
 *       resume an {@code IN_PROGRESS} run, continue an approved
 *       {@code AWAITING_APPROVAL} turn, or discard stale approvals.</li>
 *   <li><strong>Stale approval archiving</strong> — atomically archives
 *       checkpoints that were abandoned by the user in favor of a new goal,
 *       preventing history contamination.</li>
 * </ul>
 *
 * <h3>🚨 Transaction-free zone (§5.9)</h3>
 * <p>No method in this class may carry {@code @Transactional} or
 * {@code @Transactional(REQUIRED)}. The stale discard path calls
 * {@code agentStore.save(checkpoint)} which internally uses
 * {@code REQUIRES_NEW} in the SQLite adapter — that is safe. An outer
 * {@code @Transactional(REQUIRED)} here would reintroduce the P84
 * SQLITE_BUSY cascade.
 *
 * <h3>🚨 Double-resume guard (§3.4)</h3>
 * <p>The {@code finally} block that calls
 * {@code agentStore.clearBatchResuming(sessionId)} remains in
 * {@link CheckpointResumeHandler} — it is NOT moved here. This coordinator
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

    /**
     * Decision outcome for the checkpoint load phase.
     */
    public sealed interface ResumeDecision {
        /** Start a completely fresh goal. */
        record FreshStart() implements ResumeDecision {}

        /** Resume with existing history. */
        record ResumedHistory(List<ReasoningStep> history) implements ResumeDecision {}

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
    public ResumeDecision resolveResumePath(String sessionId,
                                            String canonicalRole,
                                            String capturedUserId,
                                            MessageContext context) {

        Optional<AgentCheckpoint> existingCheckpoint = agentStore.load(sessionId, canonicalRole);
        if (existingCheckpoint.isEmpty()) {
            return new ResumeDecision.FreshStart();
        }

        AgentCheckpoint checkpoint = existingCheckpoint.get();
        CheckpointStatus status = checkpoint.getStatus();

        if (status == CheckpointStatus.IN_PROGRESS) {
            List<ReasoningStep> loadedSteps = agentStore.deserializeSteps(checkpoint);
            log.infof("CheckpointResumeCoordinator: restored %d steps from IN_PROGRESS for session=%s",
                    loadedSteps != null ? loadedSteps.size() : 0, sessionId);
            return new ResumeDecision.ResumedHistory(loadedSteps != null ? loadedSteps : new ArrayList<>());
        }

        if (status == CheckpointStatus.AWAITING_APPROVAL) {
            boolean isResume = context != null && context.isResumeSignal();
            if (isResume) {
                List<ReasoningStep> loadedSteps = agentStore.deserializeSteps(checkpoint);
                log.infof("CheckpointResumeCoordinator: restored %d steps from AWAITING_APPROVAL (resume signal) for session=%s",
                        loadedSteps != null ? loadedSteps.size() : 0, sessionId);
                return new ResumeDecision.ResumedHistory(loadedSteps != null ? loadedSteps : new ArrayList<>());
            } else {
                // Stale approval discard path (§3.4)
                archiveStaleApproval(sessionId, canonicalRole, capturedUserId, checkpoint, context);
                return new ResumeDecision.AbortedStale();
            }
        }

        // Terminal status path: Archive to keep the "latest" query clean for future runs
        log.debugf("CheckpointResumeCoordinator: ignoring terminal checkpoint (status=%s). Archiving.", status);
        checkpoint.setStatus(CheckpointStatus.ARCHIVED);
        agentStore.save(checkpoint);
        return new ResumeDecision.FreshStart();
    }

    private void archiveStaleApproval(String sessionId,
                                      String canonicalRole,
                                      String capturedUserId,
                                      AgentCheckpoint checkpoint,
                                      MessageContext context) {
        String staleTool = checkpoint.getPendingToolName();
        log.warnf("CheckpointResumeCoordinator: STALE-APPROVAL session=%s had pending tool '%s' "
                + "but received new message. Archiving and starting fresh.", sessionId, staleTool);

        checkpoint.setStatus(CheckpointStatus.ARCHIVED);
        checkpoint.setFailReason("Discarded: new user message received before approval was given.");
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        agentStore.save(checkpoint);

        // Notify user best-effort
        if (context != null && context.getUser() != null) {
            notifyStaleApprovalDiscarded(sessionId, staleTool, context);
        }

        auditService.record(sessionId, capturedUserId, canonicalRole,
                SecurityEventType.CHECKPOINT_FAILED,
                "Stale AWAITING_APPROVAL discarded — new message received. tool=" + staleTool);
    }

    private void notifyStaleApprovalDiscarded(String sessionId,
                                              String staleTool,
                                              MessageContext context) {
        try {
            String toolLabel = staleTool != null ? "`" + staleTool + "`" : "a pending tool";
            String notice = "⚠️ A previous approval request for " + toolLabel
                    + " was discarded because you sent a new message. "
                    + "Starting fresh with your new goal.";

            channelPluginRegistry.find(context.getUser().channel().id())
                    .map(ChannelPlugin::sender)
                    .ifPresent(sender -> sender.sendRaw(context.getUser(), notice));
        } catch (Exception e) {
            log.warnf("CheckpointResumeCoordinator.notifyStaleApprovalDiscarded: failed for session %s: %s",
                    sessionId, AgentErrorMessageUtil.safeMessage(e));
        }
    }
}
