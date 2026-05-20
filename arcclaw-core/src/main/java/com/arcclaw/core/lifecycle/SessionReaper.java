package com.arcclaw.core.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.hitl.ApprovalDecision;
import com.arcclaw.channel.api.hitl.ApprovalDecisionEvent;
import com.arcclaw.channel.api.hitl.ApprovalOutcome;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.loop.CliResultBridge;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.audit.SessionAuditSequencer;
import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Orchestrates scheduled session cleanup and zombie recovery.
 * <p>
 * Transaction pattern - mirrors AgentCronRepository WAL-safe split:
 * - Reads use NOT_SUPPORTED (no write lock acquired)
 * - Writes use REQUIRED (atomic micro-short transactions per row)
 * - Scheduled entry points have NO @Transactional (delegate to split methods)
 * This prevents SQLITE_BUSY / "cannot commit - no transaction is active" errors
 * caused by Narayana XA wrapping reads that SQLite has already auto-committed.
 */
@ApplicationScoped
public class SessionReaper {

    @Inject MeterRegistry meterRegistry;

    private static final Logger log = Logger.getLogger(SessionReaper.class);

    private volatile boolean shuttingDown = false;

    @Inject WorkspaceManager workspaceManager;
    @Inject SessionHeartbeatTracker heartbeatTracker;
    @Inject Event<ApprovalDecisionEvent> decisionEvents;
    @Inject StatefulAgentStore agentStore;
    @Inject ObjectMapper objectMapper;
    @Inject SessionAuditSequencer auditSequencer;
    @Inject CheckpointStorePort checkpointStore;
    @Inject CompactionInFlightFlag compactionInFlight;
    @Inject AgentSseService agentSseService;
    @Inject CliResultBridge cliResultBridge;

    @ConfigProperty(name = "arcclaw.workspace.session-ttl-minutes", defaultValue = "30")
    long sessionTtlMinutes;

    @ConfigProperty(name = "arcclaw.hitl.timeout-hours", defaultValue = "24")
    long hitlTimeoutHours;

    @ConfigProperty(name = "arcclaw.chat-memory.retention-days", defaultValue = "30")
    long retentionDays;

    void onShutdown(@Observes ShutdownEvent event) {
        shuttingDown = true;
    }

    /**
     * On application startup, find any IN_PROGRESS or AWAITING_APPROVAL
     * checkpoints from a previous JVM crash and mark them as FAILED.
     *
     * NO @Transactional annotation - actual transactional boundaries and 
     * SQLite retry loops are handled by the delegated methods within 
     * CheckpointStorePort to prevent nested REQUIRES_NEW overhead.
     */
    public void onStartup(@Observes StartupEvent event) {
        try {
            // Note: SQLite BUSY retries are handled internally (transparently) 
            // by CheckpointStorePort via SqliteRetryTemplate. 
            // FIXED (§FIX 3): No nested REQUIRES_NEW. recoverZombiesAndMarkFailed handles full tx.
            doRecoverZombies();
        } catch (Exception e) {
            log.errorf("Failed onStartup zombie recovery: %s", LogUtil.rootMessage(e));
            log.debug("Full stacktrace for onStartup zombie recovery failure", e);
        }
    }

    private void doRecoverZombies() {
        Instant inProgressCutoff = Instant.now().minus(Duration.ofMinutes(15));
        
        // Step 1: Mark IN_PROGRESS zombies FAILED
        List<CheckpointDto> failed = checkpointStore.recoverInProgressZombies(
            inProgressCutoff, "JVM crash recovery - orphaned at startup");
        if (!failed.isEmpty()) {
            log.warnf("Zombie recovery: found and failed %d orphaned IN_PROGRESS checkpoint(s).",
                failed.size());
            failed.forEach(dto -> log.infof("  Marked FAILED: id=%d session=%s role=%s",
                dto.id(), dto.sessionId(), dto.agentRole()));
        } else {
            log.info("Zombie recovery: no orphaned IN_PROGRESS checkpoints found.");
        }

        // Step 2: Replay surviving AWAITING_APPROVAL - never orphan at startup
        // CLI approvals are auto-denied (CLI has no SSE subscription to replay into)
        List<CheckpointDto> pendingApprovals =
            checkpointStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name());
        if (!pendingApprovals.isEmpty()) {
            log.infof("Zombie recovery: found %d AWAITING_APPROVAL checkpoint(s).", pendingApprovals.size());
            for (var dto : pendingApprovals) {
                if (ChannelType.CLI.id().equalsIgnoreCase(dto.channelType())) {
                    log.warnf("  Auto-denying CLI approval: id=%d session=%s tool=%s",
                        dto.id(), dto.sessionId(), dto.pendingToolName());
                    checkpointStore.transitionStatus(dto.id(), CheckpointStatus.DENIED.name());
                    cliResultBridge.cancel(dto.sessionId());
                } else {
                    log.infof("  Surviving approval: id=%d session=%s role=%s tool=%s",
                        dto.id(), dto.sessionId(), dto.agentRole(), dto.pendingToolName());
                    agentSseService.republishOnSubscribe(
                        dto.sessionId(), dto.pendingToolName(), dto.pendingToolParametersJson());
                }
            }
        } else {
            log.info("Zombie recovery: no orphaned AWAITING_APPROVAL checkpoints found.");
        }
    }

    // GC SWEEP - terminates expired sessions
    
    /**
     * Scheduled GC sweep - terminates sessions whose heartbeat exceeds the TTL.
     * Skips sessions that are AWAITING_APPROVAL (handled by sweepTimedOutApprovals).
     * NO @Transactional - delegates to split read/write methods.
     */
    @Scheduled(every = "${arcclaw.workspace.gc-interval:5m}", delayed = "PT90S")
    public void gcSweep() {
        if (shuttingDown) return;
        if (heartbeatTracker.activeSessionCount() == 0) return;

        try {
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(sessionTtlMinutes));
            log.debugf("GC sweep: checking sessions against TTL=%dm (cutoff=%s)",
                    sessionTtlMinutes, cutoff);

            Set<String> awaitingApprovalIds = findAwaitingApprovalIds();

            heartbeatTracker.forEachExpired(cutoff, sessionId -> {
                if (awaitingApprovalIds.contains(sessionId)) {
                    log.debugf("GC sweep: skipping session=%s (AWAITING_APPROVAL)", sessionId);
                    return;
                }
                if (agentStore.isBatchResuming(sessionId)) {
                    log.infof("GC sweep: skipping session=%s (RESUMING)", sessionId);
                    return;
                }
                log.warnf("GC sweep: session=%s expired", sessionId);
                meterRegistry.counter("arcclaw.agent.orphaned.sweep").increment();
                cliResultBridge.cancel(sessionId); 
                compactionInFlight.remove(sessionId);
                workspaceManager.terminateSession(sessionId);
                heartbeatTracker.removeHeartbeat(sessionId);
                auditSequencer.releaseStaleSession(sessionId);
            });
        } catch (IllegalStateException | PersistenceException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean isShutdown = shuttingDown || msg.contains("EntityManagerFactory is closed");
            if (!isShutdown) {
                log.error("SessionReaper: gcSweep failed", e);
            } else {
                log.infof("SessionReaper: database closed during gcSweep (app shutting down) - suppression intentional.");
                log.debugf("Full shutdown race details:\n%s", LogUtil.trimmedStack(e));
            }
        } catch (Exception e) {
            log.error("SessionReaper: gcSweep failed", e);
        }
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    Set<String> findAwaitingApprovalIds() {
        return checkpointStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name())
                .stream()
                .map(CheckpointDto::sessionId)
                .collect(Collectors.toSet());
    }

    // APPROVAL TIMEOUT SWEEP - terminates stale AWAITING_APPROVAL sessions
    
    /**
     * Terminate sessions that have been AWAITING_APPROVAL for too long.
     * Marks checkpoint FAILED and notifies user via their channel.
     * NO @Transactional - delegates to split read/write methods.
     */
    @Scheduled(every = "${arcclaw.workspace.gc-interval:5m}")
    public void sweepTimedOutApprovals() {
        if (shuttingDown) return;

        try {
            Instant cutoff = Instant.now().minus(Duration.ofHours(hitlTimeoutHours));
            List<AgentCheckpoint> stale = agentStore.findStaleAwaitingApproval(cutoff);
            if (stale.isEmpty()) return;

            for (AgentCheckpoint cp : stale) {
                log.warnf("[HITL-GC] Session %s role %s has been awaiting approval for >%dh - auto-denying.",
                        cp.getSessionId(), cp.getAgentRole(), hitlTimeoutHours);
                meterRegistry.counter("arcclaw.hitl.timeout.auto_deny",
                        "role", cp.getAgentRole()).increment();
                markApprovalTimedOut(cp);
            }
        } catch (IllegalStateException | PersistenceException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean isShutdown = shuttingDown || msg.contains("EntityManagerFactory is closed");
            if (!isShutdown) {
                log.error("SessionReaper: sweepTimedOutApprovals failed", e);
            } else {
                log.infof("SessionReaper: database closed during sweepTimedOutApprovals (app shutting down) - suppression intentional.");
                log.debugf("Full shutdown race details:\n%s", LogUtil.trimmedStack(e));
            }
        } catch (Exception e) {
            log.error("SessionReaper: sweepTimedOutApprovals failed", e);
        }
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    void markApprovalTimedOut(AgentCheckpoint cp) {
        if (cp.hasBatchToolDecisions()) {
            BatchDecisionUtil.pendingToolNames(cp, objectMapper).forEach(toolName ->
                agentStore.updateBatchToolDecision(
                    cp.getSessionId(), cp.getAgentRole(), toolName,
                    ToolDecision.DENIED.name()));
        }

        agentStore.markFailed(cp.getId(), "Approval timed out after " + hitlTimeoutHours + " hours.");

        // Notify channel
        decisionEvents.fireAsync(new ApprovalDecisionEvent(
            cp.getSessionId(),
            cp.getChannelType(),
            cp.getChannelPlatformId(),
            cp.getPendingToolName(),
            ApprovalDecision.REJECT,
            ApprovalOutcome.EXPIRED
        ));

        // Clean up SSE
        agentSseService.complete(cp.getSessionId());

        compactionInFlight.remove(cp.getSessionId());
        workspaceManager.terminateSession(cp.getSessionId());
        heartbeatTracker.removeHeartbeat(cp.getSessionId());
        auditSequencer.releaseStaleSession(cp.getSessionId());
    }

    // CHAT HISTORY RETENTION SWEEP - purges ChatMemoryEntity rows for old sessions
    
    /**
     * Purge ChatMemoryEntity rows where sessionId has no active/recent checkpoint.
     * This prevents the chat_memory table from growing unboundedly (§85-retention).
     */
    @Scheduled(every = "24h", delayed = "PT10M")
    public void purgeOldChatHistory() {
        if (shuttingDown) return;

        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        log.infof("Purging chat history older than %d days (cutoff=%s)", retentionDays, cutoff);
        
        try {
            checkpointStore.purgeOldChatHistory(cutoff);
        } catch (Exception e) {
            log.warnf("Failed to purge old chat history: %s", LogUtil.rootMessage(e));
        }
    }
}
