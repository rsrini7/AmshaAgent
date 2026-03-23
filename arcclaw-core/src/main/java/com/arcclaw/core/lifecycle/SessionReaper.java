package com.arcclaw.core.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.persistence.SqliteErrorStrings;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
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
    Event<ApprovalTimedOutEvent> approvalTimedOutEvent;

    @ConfigProperty(name = "arcclaw.workspace.session-ttl-minutes", defaultValue = "30")
    long sessionTtlMinutes;

    @ConfigProperty(name = "arcclaw.governance.approval-timeout-minutes", defaultValue = "60")
    long approvalTimeoutMinutes;

    void onShutdown(@Observes ShutdownEvent event) {
        shuttingDown = true;
    }

    /**
     * On application startup, find any IN_PROGRESS or AWAITING_APPROVAL
     * checkpoints from a previous JVM crash and mark them as FAILED.
     */
    public void onStartup(@Observes StartupEvent event) {
        int maxRetries = 5;
        long backoffMs = 50;
        Exception lastEx = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    doRecoverZombies();
                });
                return;
            } catch (Exception e) {
                lastEx = e;
                if (SqliteErrorStrings.isBusy(e)) {
                    log.warnf("SQLite BUSY on onStartup attempt %d/%d — retrying in %dms",
                            attempt, maxRetries, backoffMs * attempt);
                    try {
                        Thread.sleep(backoffMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    log.errorf("Non-retryable error during onStartup zombie recovery: %s",
                            e.getMessage());
                    log.debugf(e, "Full stacktrace for non-retryable error in onStartup zombie recovery");
                    return;
                }
            }
        }
        log.errorf("Failed onStartup zombie recovery after %d retries: %s",
                maxRetries, lastEx != null ? lastEx.getMessage() : "unknown");
        log.debugf(lastEx, "Full stacktrace for retry exhaustion in onStartup zombie recovery");
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

    // ──────────────────────────────────────────────────────────────
    // GC SWEEP — terminates expired sessions
    // ──────────────────────────────────────────────────────────────

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
            workspaceManager.terminateSession(sessionId);
            heartbeatTracker.removeHeartbeat(sessionId);
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

    // ──────────────────────────────────────────────────────────────
    // APPROVAL TIMEOUT SWEEP — terminates stale AWAITING_APPROVAL sessions
    // ──────────────────────────────────────────────────────────────

    /**
     * Terminate sessions that have been AWAITING_APPROVAL for too long.
     * Marks checkpoint FAILED and notifies user via their channel.
     * NO @Transactional — delegates to split read/write methods.
     */
    @Scheduled(every = "${arcclaw.workspace.gc-interval:5m}")
    public void sweepTimedOutApprovals() {
        if (shuttingDown) return;

        List<AgentCheckpoint> timedOut = findTimedOutApprovals();
        if (timedOut.isEmpty()) return;

        for (var cp : timedOut) {
            log.warnf("Approval timeout: session=%s role=%s — marking FAILED",
                    cp.getSessionId(), cp.getAgentRole());
            markApprovalTimedOut(cp);
        }
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    List<AgentCheckpoint> findTimedOutApprovals() {
        em.clear();
        LocalDateTime cutoff = LocalDateTime.now()
                .minus(Duration.ofMinutes(approvalTimeoutMinutes));
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", cutoff)
                .getResultList();
    }

    @Transactional(Transactional.TxType.REQUIRED)
    void markApprovalTimedOut(AgentCheckpoint cp) {
        cp.setStatus(CheckpointStatus.FAILED);
        cp.setFailedAt(Instant.now());
        cp.setFailReason("Approval timed out after " + approvalTimeoutMinutes + " minutes.");
        em.merge(cp);

        // fireAsync — non-blocking, does not enlist in SQLite XA transaction
        approvalTimedOutEvent.fireAsync(new ApprovalTimedOutEvent(
                cp.getSessionId(),
                cp.getChannelPlatformId(),
                cp.getChannelType(),
                cp.getPendingToolName(),
                approvalTimeoutMinutes
        ));

        workspaceManager.terminateSession(cp.getSessionId());
        heartbeatTracker.removeHeartbeat(cp.getSessionId());
    }
}
