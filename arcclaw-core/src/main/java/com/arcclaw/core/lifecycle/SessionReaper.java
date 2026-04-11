package com.arcclaw.core.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.channel.api.hitl.ApprovalDecision;
import com.arcclaw.channel.api.hitl.ApprovalDecisionEvent;
import com.arcclaw.channel.api.hitl.ApprovalOutcome;
import com.arcclaw.core.agent.loop.CliResultBridge;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.audit.SessionAuditSequencer;
import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.persistence.SqliteRetryTemplate;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Orchestrates scheduled session cleanup and zombie recovery.
 * <p>
 * Transaction pattern — mirrors AgentCronRepository WAL-safe split:
 * - Reads use NOT_SUPPORTED (no write lock acquired)
 * - Writes use REQUIRED (atomic micro-short transactions per row)
 * - Scheduled entry points have NO @Transactional (delegate to split methods)
 * This prevents SQLITE_BUSY / "cannot commit - no transaction is active" errors
 * caused by Narayana XA wrapping reads that SQLite has already auto-committed.
 */
@ApplicationScoped
public class SessionReaper {

    private static final Logger log = Logger.getLogger(SessionReaper.class);

    private volatile boolean shuttingDown = false;

    @Inject WorkspaceManager workspaceManager;
    @Inject EntityManager em;
    @Inject SessionHeartbeatTracker heartbeatTracker;
    @Inject Event<ApprovalDecisionEvent> decisionEvents;
    @Inject StatefulAgentStore agentStore;
    @Inject ObjectMapper objectMapper;
    @Inject SessionAuditSequencer auditSequencer;
    @Inject SqliteRetryTemplate retryTemplate;
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
     */
    public void onStartup(@Observes StartupEvent event) {
        try {
            retryTemplate.executeVoid("onStartup-zombies", 5, 50, () -> {
                QuarkusTransaction.requiringNew().run(this::doRecoverZombies);
            });
        } catch (Exception e) {
            log.errorf("Failed onStartup zombie recovery after retries: %s", e.getMessage());
            log.debugf(e, "Full stacktrace for onStartup zombie recovery failure");
        }
    }

    private void doRecoverZombies() {
        // 1. Recover IN_PROGRESS zombies — mark FAILED (true crash orphans)
        // D1.2: Added 15-minute grace period based on updatedAt to protect
        // active checkpoints running on other nodes during rolling/canary deployments.
        // Convert Instant → LocalDateTime to match the column type (updatedAt is LocalDateTime).
        // Without this, SQLite compares TEXT representations of Instant vs LocalDateTime incorrectly.
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                Instant.now().minus(Duration.ofMinutes(15)),
                ZoneOffset.UTC);
        List<AgentCheckpoint> inProgressZombies = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.IN_PROGRESS)
                .setParameter("cutoff", cutoff)
                .getResultList();

        if (!inProgressZombies.isEmpty()) {
            log.warnf("Zombie recovery: found %d orphaned IN_PROGRESS checkpoint(s) — marking FAILED",
                    inProgressZombies.size());
            Instant now = Instant.now();
            for (var checkpoint : inProgressZombies) {
                checkpoint.setStatus(CheckpointStatus.FAILED);
                checkpoint.setFailedAt(now);
                checkpoint.setFailReason("JVM crash recovery — session was IN_PROGRESS at startup");
                em.merge(checkpoint);
                log.infof("  Marked checkpoint id=%d (session=%s, role=%s) as FAILED",
                        checkpoint.getId(), checkpoint.getSessionId(), checkpoint.getAgentRole());
            }
        } else {
            log.info("Zombie recovery: no orphaned IN_PROGRESS checkpoints found.");
        }

        // 2. AWAITING_APPROVAL — leave alive, queue SSE replay for when UI connects
        List<AgentCheckpoint> approvalZombies = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .getResultList();

        if (!approvalZombies.isEmpty()) {
            log.infof("Zombie recovery: found %d AWAITING_APPROVAL checkpoint(s) — leaving as-is, queuing SSE replay",
                    approvalZombies.size());
            for (var checkpoint : approvalZombies) {
                log.infof("  Surviving approval: id=%d session=%s role=%s tool=%s",
                        checkpoint.getId(),
                        checkpoint.getSessionId(),
                        checkpoint.getAgentRole(),
                        checkpoint.getPendingToolName());
                // Queue the event so UI gets it when it subscribes after restart
                agentSseService.republishOnSubscribe(
                    checkpoint.getSessionId(),
                    checkpoint.getPendingToolName(),
                    checkpoint.getPendingToolParametersJson()
                );
            }
        } else {
            log.info("Zombie recovery: no orphaned AWAITING_APPROVAL checkpoints.");
        }
    }

    // GC SWEEP — terminates expired sessions
    
    /**
     * Scheduled GC sweep — terminates sessions whose heartbeat exceeds the TTL.
     * Skips sessions that are AWAITING_APPROVAL (handled by sweepTimedOutApprovals).
     * NO @Transactional — delegates to split read/write methods.
     */
    @Scheduled(every = "${arcclaw.workspace.gc-interval:5m}", delayed = "PT90S")
    public void gcSweep() {
        if (shuttingDown) return;
        if (heartbeatTracker.activeSessionCount() == 0) return;

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
            cliResultBridge.cancel(sessionId); 
            compactionInFlight.remove(sessionId);
            workspaceManager.terminateSession(sessionId);
            heartbeatTracker.removeHeartbeat(sessionId);
            auditSequencer.releaseStaleSession(sessionId);
        });
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    Set<String> findAwaitingApprovalIds() {
        em.clear();
        return em.createQuery(
                "SELECT c.sessionId FROM AgentCheckpoint c WHERE c.status = :status",
                String.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .getResultStream()
                .collect(Collectors.toSet());
    }

    // APPROVAL TIMEOUT SWEEP — terminates stale AWAITING_APPROVAL sessions
    
    /**
     * Terminate sessions that have been AWAITING_APPROVAL for too long.
     * Marks checkpoint FAILED and notifies user via their channel.
     * NO @Transactional — delegates to split read/write methods.
     */
    @Scheduled(every = "${arcclaw.workspace.gc-interval:5m}")
    public void sweepTimedOutApprovals() {
        if (shuttingDown) return;

        Instant cutoff = Instant.now().minus(Duration.ofHours(hitlTimeoutHours));
        List<AgentCheckpoint> stale = agentStore.findStaleAwaitingApproval(cutoff);
        if (stale.isEmpty()) return;

        for (AgentCheckpoint cp : stale) {
            log.warnf("[HITL-GC] Session %s role %s has been awaiting approval for >%dh — auto-denying.",
                    cp.getSessionId(), cp.getAgentRole(), hitlTimeoutHours);
            markApprovalTimedOut(cp);
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

        AgentCheckpoint fresh = agentStore.load(cp.getSessionId(), cp.getAgentRole())
            .orElse(cp);
        fresh.setStatus(CheckpointStatus.FAILED);
        fresh.setFailedAt(Instant.now());
        fresh.setFailReason("Approval timed out after " + hitlTimeoutHours + " hours.");
        em.merge(fresh);

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

    // CHAT HISTORY RETENTION SWEEP — purges ChatMemoryEntity rows for old sessions
    
    /**
     * Purge ChatMemoryEntity rows where sessionId has no active/recent checkpoint.
     * This prevents the chat_memory table from growing unboundedly (§85-retention).
     */
    @Scheduled(every = "24h", delayed = "PT10M")
    public void purgeOldChatHistory() {
        if (shuttingDown) return;

        LocalDateTime cutoff = LocalDateTime.ofInstant(
                Instant.now().minus(Duration.ofDays(retentionDays)),
                ZoneOffset.UTC);

        log.infof("Purging chat history older than %d days (cutoff=%s)", retentionDays, cutoff);

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                int deleted = em.createQuery(
                    "DELETE FROM ChatMemoryEntity m WHERE m.memoryId NOT IN (" +
                    "  SELECT c.sessionId FROM AgentCheckpoint c " +
                    "  WHERE c.updatedAt > :cutoff " +
                    "     OR c.status NOT IN (:terminalStatuses)" +
                    ")")
                    .setParameter("cutoff", cutoff)
                    .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                    .executeUpdate();
                
                if (deleted > 0) {
                    log.infof("Purge complete: removed %d stale chat memory row(s).", deleted);
                }
            });
        } catch (Exception e) {
            log.warnf("Failed to purge old chat history: %s", e.getMessage());
        }
    }
}
