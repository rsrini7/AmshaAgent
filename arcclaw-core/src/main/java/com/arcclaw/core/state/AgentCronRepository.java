package com.arcclaw.core.state;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

/**
 * Repository for {@link AgentCronEntity} with SQLite WAL-friendly
 * transaction patterns.
 * <p>
 * Read methods use {@code NOT_SUPPORTED} to avoid write locks.
 * Write methods use {@code REQUIRED} or {@code REQUIRES_NEW} for atomic micro-short transactions.
 * This split prevents {@code SQLITE_BUSY} on the main Telegram read paths.
 */
@ApplicationScoped
public class AgentCronRepository {

    @Inject
    EntityManager em;


    // ──────────────────────────────────────────────────────────────
    // READ-ONLY — MUST NOT open a write transaction
    // ──────────────────────────────────────────────────────────────

    /**
     * Finds all active cron jobs whose next fire time is at or before now.
     * Read-only — no write lock acquired.
     *
     * NOTE: em.clear() is intentionally omitted here.
     * NOT_SUPPORTED suspends any outer transaction and runs without a persistence
     * context — there are no managed entities in scope, so em.clear() would be
     * a no-op. Stale L1 cache is not a concern here because each call gets a
     * fresh EntityManager scope with no prior state.
     */
    @Transactional(TxType.NOT_SUPPORTED)
    @SuppressWarnings("unchecked")
    public List<AgentCronEntity> findDueCrons() {
        String now = InstantToTextConverter.format(Instant.now());
        return em.createNativeQuery(
                "SELECT * FROM agentcron WHERE status='ACTIVE' AND nextFireTime <= :now",
                AgentCronEntity.class
        ).setParameter("now", now).getResultList();
    }

    /**
     * Tenant-isolated query: returns all cron jobs for the given user.
     *
     * NOTE: em.clear() is intentionally kept here.
     * SUPPORTS joins the caller's outer transaction if one exists (e.g. Telegram
     * message handler). Without clear(), the L1 cache would return stale managed
     * entities from earlier in the same transaction, hiding freshly committed DB
     * changes — this was the root cause of the "chat mode changes not immediate" bug.
     */
    @Transactional(TxType.SUPPORTS)
    public List<AgentCronEntity> findByUserId(String userId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCronEntity c WHERE c.userId = :userId",
                AgentCronEntity.class
        ).setParameter("userId", userId).getResultList();
    }

    /**
     * Finds all cron jobs awaiting user approval for a specific user.
     *
     * NOTE: em.clear() is intentionally kept here — same reason as findByUserId().
     * SUPPORTS joins outer transaction; clear() prevents stale L1 reads in
     * Telegram/chat approval flows where the AWAITING_APPROVAL write and this
     * read may share the same persistence context.
     */
    @Transactional(TxType.SUPPORTS)
    public List<AgentCronEntity> findAwaitingApprovalByUserId(String userId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCronEntity c WHERE c.userId = :userId AND c.status = 'AWAITING_APPROVAL'",
                AgentCronEntity.class
        ).setParameter("userId", userId).getResultList();
    }


    // ──────────────────────────────────────────────────────────────
    // WRITE — atomic micro-short transactions
    // ──────────────────────────────────────────────────────────────

    /**
     * Atomic claim — prevents double-fire across concurrent poll ticks.
     *
     * @return true if the row was claimed (status changed ACTIVE→RUNNING)
     */
    @Transactional(TxType.REQUIRED)
    public boolean claimForFire(String id) {
        int updated = em.createNativeQuery(
                "UPDATE agentcron SET status='RUNNING' WHERE id=:id AND status='ACTIVE'"
        ).setParameter("id", id).executeUpdate();
        return updated == 1;
    }

    /**
     * Releases a claimed job back to ACTIVE for next poll.
     * Used for throttle recovery — NEVER call markFailed for throttled jobs.
     */
    @Transactional(TxType.REQUIRED)
    public void releaseClaimToActive(String id) {
        em.createNativeQuery(
                "UPDATE agentcron SET status='ACTIVE' WHERE id=:id AND status='RUNNING'"
        ).setParameter("id", id).executeUpdate();
    }

    /**
     * Recurring job: advance nextFireTime, keep ACTIVE.
     */
    @Transactional(TxType.REQUIRED)
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        em.createNativeQuery(
                "UPDATE agentcron SET status='ACTIVE', lastFireTime=:last, nextFireTime=:next, updatedAt=:now WHERE id=:id"
        ).setParameter("last", InstantToTextConverter.format(lastFireTime))
         .setParameter("next", InstantToTextConverter.format(nextFireTime))
         .setParameter("now", InstantToTextConverter.format(Instant.now()))
         .setParameter("id", id)
         .executeUpdate();
    }

    /**
     * One-shot job: terminal state, never rescheduled.
     */
    @Transactional(TxType.REQUIRED)
    public void markCompleted(String id, Instant lastFireTime) {
        em.createNativeQuery(
                "UPDATE agentcron SET status='COMPLETED', lastFireTime=:last, updatedAt=:now WHERE id=:id"
        ).setParameter("last", InstantToTextConverter.format(lastFireTime))
         .setParameter("now", InstantToTextConverter.format(Instant.now()))
         .setParameter("id", id)
         .executeUpdate();
    }

    /**
     * HITL pause: prevents re-fire loop by moving to a dedicated status.
     */
    @Transactional(TxType.REQUIRED)
    public void markAwaitingApproval(String id) {
        em.createNativeQuery(
                "UPDATE agentcron SET status='AWAITING_APPROVAL', updatedAt=:now WHERE id=:id"
        ).setParameter("now", InstantToTextConverter.format(Instant.now()))
         .setParameter("id", id)
         .executeUpdate();
    }

    /**
     * Idempotency key — write BEFORE Telegram send.
     * Provides at-least-once delivery observability.
     */
    @Transactional(TxType.REQUIRED)
    public void setDeliveryId(String id, String deliveryId) {
        em.createNativeQuery(
                "UPDATE agentcron SET lastDeliveryId=:d WHERE id=:id"
        ).setParameter("d", deliveryId)
         .setParameter("id", id)
         .executeUpdate();
    }

    /**
     * Failure recording — terminal state.
     */
    @Transactional(TxType.REQUIRED)
    public void markFailed(String id, String errorReason) {
        em.createNativeQuery(
                "UPDATE agentcron SET status='FAILED', errorReason=:err, updatedAt=:now WHERE id=:id"
        ).setParameter("err", errorReason)
         .setParameter("now", InstantToTextConverter.format(Instant.now()))
         .setParameter("id", id)
         .executeUpdate();
    }

    /**
     * Skip jobs that missed their lookback window.
     * Updates status to SKIPPED and advances nextFireTime.
     */
    @Transactional(TxType.REQUIRED)
    public void markSkipped(List<AgentCronEntity> skippedJobs) {
        if (skippedJobs.isEmpty()) return; // avoid acquiring write lock for nothing
        String now = InstantToTextConverter.format(Instant.now());
        for (AgentCronEntity job : skippedJobs) {
            em.createNativeQuery(
                "UPDATE agentcron SET status='SKIPPED', nextFireTime=:next, updatedAt=:now WHERE id=:id"
            ).setParameter("next", InstantToTextConverter.format(job.nextFireTime))
             .setParameter("now", now)
             .setParameter("id", job.id)
             .executeUpdate();
        }
    }

    /**
     * Crash/OOM recovery: resets RUNNING rows stuck longer than threshold.
     * Rows with null lastFireTime (claimed but never started) use createdAt
     * as fallback.
     */
    @Transactional(TxType.REQUIRED)
    public void reclaimStaleCrons(Instant threshold) {
        String thresholdStr = InstantToTextConverter.format(threshold);
        em.createNativeQuery(
                "UPDATE agentcron SET status='ACTIVE' " +
                "WHERE status='RUNNING' AND " +
                "(lastFireTime < :threshold OR (lastFireTime IS NULL AND createdAt < :threshold))"
        ).setParameter("threshold", thresholdStr).executeUpdate();
    }

    /**
     * Persist new cron job.
     */
    @Transactional(TxType.REQUIRED)
    public void persist(AgentCronEntity entity) {
        em.persist(entity);
    }

    /**
     * Tenant-isolated delete. User B cannot delete User A's cron row.
     */
    @Transactional(TxType.REQUIRED)
    public int deleteByIdAndUserId(String id, String userId) {
        return em.createQuery(
                "DELETE FROM AgentCronEntity c WHERE c.id = :id AND c.userId = :userId"
        ).setParameter("id", id)
         .setParameter("userId", userId)
         .executeUpdate();
    }

    /**
     * Wipes ALL cron jobs for a user across ALL sessions.
     *
     * <p><strong>INTENDED FOR ACCOUNT LIFECYCLE ONLY</strong> — use this for:
     * <ul>
     *   <li>Account deletion</li>
     *   <li>GDPR/DPDP data erasure</li>
     *   <li>Admin-initiated user ban</li>
     * </ul>
     *
     * <p><strong>DO NOT use for session reset or terminate</strong> — that would
     * silently wipe scheduled tasks from other active sessions belonging to the
     * same user. Use {@link #deleteAllBySessionId(String)} instead.
     *
     * <p>REQUIRES_NEW ensures this commits independently — caller rollback
     * cannot silently undo a destructive bulk delete.
     */
    @Transactional(TxType.REQUIRES_NEW)
    public long deleteAllByUserId(String userId) {
        return em.createQuery(
                "DELETE FROM AgentCronEntity c WHERE c.userId = :userId")
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /**
     * Session-scoped bulk delete — used by ResetReflex to clear only the
     * cron jobs belonging to the reset session, leaving other sessions'
     * scheduled tasks untouched.
     * REQUIRES_NEW ensures this commits independently — caller rollback
     * cannot silently undo a destructive bulk delete.
     */
    @Transactional(TxType.REQUIRES_NEW)
    public long deleteAllBySessionId(String sessionId) {
        return em.createQuery(
                "DELETE FROM AgentCronEntity c WHERE c.sessionId = :sessionId")
                 .setParameter("sessionId", sessionId)
                 .executeUpdate();
    }
}
