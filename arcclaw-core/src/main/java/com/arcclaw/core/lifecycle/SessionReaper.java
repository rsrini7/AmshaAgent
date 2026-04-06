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
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.audit.SessionAuditSequencer;
import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.persistence.SqliteRetryTemplate;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
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

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    EntityManager em;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    Event<StaleApprovalExpired> staleApprovalEvent;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SessionAuditSequencer auditSequencer;

    @Inject
    SqliteRetryTemplate retryTemplate;

    @Inject
    CompactionInFlightFlag compactionInFlight;

    @ConfigProperty(name = "arcclaw.workspace.session-ttl-minutes", defaultValue = "30")
    long sessionTtlMinutes;

    @ConfigProperty(name = "arcclaw.hitl.timeout-hours", defaultValue = "24")
    long hitlTimeoutHours;

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
        // 1. Recover IN_PROGRESS zombies
        List<AgentCheckpoint> inProgressZombies = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.IN_PROGRESS)
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

        // 2. Recover AWAITING_APPROVAL zombies
        List<AgentCheckpoint> approvalZombies = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .getResultList();

        if (!approvalZombies.isEmpty()) {
            log.warnf("Zombie recovery: found %d orphaned AWAITING_APPROVAL checkpoint(s) — marking FAILED",
                    approvalZombies.size());
            Instant now = Instant.now();
            for (var checkpoint : approvalZombies) {
                checkpoint.setStatus(CheckpointStatus.FAILED);
                checkpoint.setFailedAt(now);
                checkpoint.setFailReason("JVM crash recovery: approval was pending at shutdown");
                em.merge(checkpoint);
                log.infof("  Marked checkpoint id=%d (session=%s, role=%s) as FAILED",
                        checkpoint.getId(), checkpoint.getSessionId(), checkpoint.getAgentRole());
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
    @Scheduled(every = "${arcclaw.workspace.gc-interval:5m}")
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
            log.warnf("GC sweep: session=%s expired", sessionId);
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

    @Transactional(Transactional.TxType.REQUIRED)
    void markApprovalTimedOut(AgentCheckpoint cp) {
        // Auto-deny all pending tools if it's a batch
        if (cp.hasBatchToolDecisions()) {
            BatchDecisionUtil.pendingToolNames(cp, objectMapper).forEach(toolName ->
                agentStore.updateBatchToolDecision(
                    cp.getSessionId(), cp.getAgentRole(), toolName,
                    AgentSentinels.TOOL_DECISION_DENIED));
        }

        // Reload to pick up the decisions written by REQUIRES_NEW transactions above
        AgentCheckpoint fresh = agentStore.load(cp.getSessionId(), cp.getAgentRole())
            .orElse(cp);
        fresh.setStatus(CheckpointStatus.FAILED);
        fresh.setFailedAt(Instant.now());
        fresh.setFailReason("Approval timed out after " + hitlTimeoutHours + " hours.");
        em.merge(fresh);

        // Notify user via channel event
        staleApprovalEvent.fireAsync(new StaleApprovalExpired(
                cp.getSessionId(),
                cp.getAgentRole(),
                null, // userId not stored in AgentCheckpoint — derive from session if needed by observers
                cp.getChannelType(),
                cp.getChannelPlatformId(),
                cp.getPendingToolName()
        ));

        compactionInFlight.remove(cp.getSessionId());
        workspaceManager.terminateSession(cp.getSessionId());
        heartbeatTracker.removeHeartbeat(cp.getSessionId());
        auditSequencer.releaseStaleSession(cp.getSessionId());
    }
}
