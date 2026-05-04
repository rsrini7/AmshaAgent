package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.util.TemporalUtils;
import org.jboss.logging.Logger;

/**
 * Handles status transitions and lifecycle marking for agent checkpoints.
 * Optimized for SQLite via Native SQL to minimize lock duration and avoid JTA deadlocks.
 */
@ApplicationScoped
public class CheckpointStatusService {

    private static final Logger log = Logger.getLogger(CheckpointStatusService.class);

    private final EntityManager em;

    @Inject
    public CheckpointStatusService(EntityManager em) {
        this.em = em;
    }

    protected CheckpointStatusService() {
        this(null);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doTransitionStatus(Long id, String newStatus) {
        log.infof("[StatusService] Transitioning checkpoint %d to %s", id, newStatus);
        em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = :new, updated_at = :now " +
                "WHERE checkpoint_id = :id")
                .setParameter("new", newStatus)
                .setParameter("now", TemporalUtils.formatIsoLex(Instant.now()))
                .setParameter("id", id)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doAtomicTransition(Long id, String expectedStatus, String newStatus) {
        log.infof("[StatusService] Atomic transition checkpoint %d: %s -> %s", id, expectedStatus, newStatus);
        int updated = em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = :new, updated_at = :now " +
                "WHERE checkpoint_id = :id AND status = :expected"
        ).setParameter("new", newStatus)
         .setParameter("now", TemporalUtils.formatIsoLex(Instant.now()))
         .setParameter("id", id)
         .setParameter("expected", expectedStatus)
         .executeUpdate();
        
        boolean success = updated == 1;
        if (!success) {
            log.debugf("[StatusService] Atomic transition failed for %d (expected %s)", id, expectedStatus);
        }
        return success;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doClaimForResume(String sessionId, String agentRole, int staleClaimMinutes) {
        log.infof("[StatusService] Claiming resume for session=%s role=%s", sessionId, agentRole);
        Instant cutoff = Instant.now().minus(staleClaimMinutes, ChronoUnit.MINUTES);
        String cutoffStr = TemporalUtils.formatIsoLex(cutoff);
        String nowStr = TemporalUtils.formatIsoLex(Instant.now());

        int updated = em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = 'RESUMING', updated_at = :now " +
                "WHERE checkpoint_id = (SELECT checkpoint_id FROM agent_checkpoint " +
                "            WHERE session_id = :sid AND agent_role = :role " +
                "            ORDER BY checkpoint_id DESC LIMIT 1) " +
                "  AND (status = 'AWAITING_APPROVAL' OR (status IN ('RESUMING', 'IN_PROGRESS') AND updated_at < :cutoff))"
        ).setParameter("sid", sessionId)
         .setParameter("role", agentRole)
         .setParameter("now", nowStr)
         .setParameter("cutoff", cutoffStr)
         .executeUpdate();
        
        boolean success = updated == 1;
        log.infof("[StatusService] Resume claim result for session %s: %b", sessionId, success);
        return success;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doReleaseResumeClaim(String sessionId, String agentRole) {
        log.infof("[StatusService] Releasing resume claim for session=%s role=%s", sessionId, agentRole);
        em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = 'AWAITING_APPROVAL', updated_at = :now " +
                "WHERE checkpoint_id = (SELECT checkpoint_id FROM agent_checkpoint " +
                "            WHERE session_id = :sid AND agent_role = :role " +
                "            ORDER BY checkpoint_id DESC LIMIT 1) " +
                "  AND status = 'RESUMING'"
        ).setParameter("sid", sessionId)
         .setParameter("role", agentRole)
         .setParameter("now", TemporalUtils.formatIsoLex(Instant.now()))
         .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass) {
        log.warnf("[StatusService] Marking checkpoint %d as FAILED: %s (code=%s)", checkpointId, failReason, errorCode);
        String nowStr = TemporalUtils.formatIsoLex(Instant.now());
        em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = 'FAILED', fail_reason = :reason, " +
                "error_code = :code, trace_id = :trace, root_cause_class = :root, " +
                "failed_at = :failedAt, updated_at = :updatedAt WHERE checkpoint_id = :id")
                .setParameter("reason", failReason)
                .setParameter("code", errorCode)
                .setParameter("trace", traceId)
                .setParameter("root", rootCauseClass)
                .setParameter("failedAt", nowStr)
                .setParameter("updatedAt", nowStr)
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkCompleted(Long checkpointId) {
        log.infof("[StatusService] Marking checkpoint %d as COMPLETED", checkpointId);
        String nowStr = TemporalUtils.formatIsoLex(Instant.now());
        em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = 'COMPLETED', completed_at = :completedAt, updated_at = :updatedAt " +
                "WHERE checkpoint_id = :id")
                .setParameter("completedAt", nowStr)
                .setParameter("updatedAt", nowStr)
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkOrphaned(Long checkpointId, String reason) {
        log.warnf("[StatusService] Marking checkpoint %d as ORPHANED: %s", checkpointId, reason);
        String nowStr = TemporalUtils.formatIsoLex(Instant.now());
        em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = 'ORPHANED', fail_reason = :reason, " +
                "failed_at = :failedAt, updated_at = :updatedAt WHERE checkpoint_id = :id")
                .setParameter("reason", reason)
                .setParameter("failedAt", nowStr)
                .setParameter("updatedAt", nowStr)
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doArchive(Long checkpointId) {
        log.infof("[StatusService] Archiving checkpoint %d", checkpointId);
        em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = 'ARCHIVED', updated_at = :now " +
                "WHERE checkpoint_id = :id")
                .setParameter("now", TemporalUtils.formatIsoLex(Instant.now()))
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doClaimForRetry(Long id) {
        log.infof("[StatusService] Claiming checkpoint %d for retry", id);
        int updated = em.createNativeQuery(
                "UPDATE agent_checkpoint SET status = 'IN_PROGRESS', retry_after_ms = NULL, updated_at = :now " +
                "WHERE checkpoint_id = :id AND status = 'AWAITING_RETRY'")
                .setParameter("now", TemporalUtils.formatIsoLex(Instant.now()))
                .setParameter("id", id)
                .executeUpdate();
        
        return updated == 1;
    }
}
