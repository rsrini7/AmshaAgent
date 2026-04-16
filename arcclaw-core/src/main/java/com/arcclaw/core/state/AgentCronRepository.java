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
    public List<AgentCronEntity> findDueCrons() {
        return em.createQuery(
                "SELECT c FROM AgentCronEntity c WHERE c.status = :status AND c.nextFireTime <= :now",
                AgentCronEntity.class
        ).setParameter("status", CronStatus.ACTIVE)
         .setParameter("now", Instant.now())
         .getResultList();
    }

    /**
     * Tenant-isolated query: returns all cron jobs for the given user.
     *
     * NOTE: em.clear() is intentionally kept here.
     * REQUIRED opens a new transaction when called standalone, and joins the
     * caller's outer transaction when one exists. em.clear() prevents stale
     * L1 cache reads — was the root cause of "chat mode changes not immediate"
     * bug when the write and this read shared the same persistence context.
     */
    @Transactional(TxType.REQUIRED)
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
     * REQUIRED opens a new transaction when called standalone (e.g. DenyReflex
     * step 5 with no outer TX), and joins the caller's outer transaction when one
     * exists (e.g. Telegram message handler). em.clear() in both cases evicts
     * stale L1 cache so the query always sees the latest committed DB state —
     * critical when an AWAITING_APPROVAL write and this read share the same
     * persistence context.
     */
    @Transactional(TxType.REQUIRED)
    public List<AgentCronEntity> findAwaitingApprovalByUserId(String userId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCronEntity c WHERE c.userId = :userId AND c.status = :status",
                AgentCronEntity.class
        ).setParameter("userId", userId)
         .setParameter("status", CronStatus.AWAITING_APPROVAL)
         .getResultList();
    }

    @Transactional(TxType.NOT_SUPPORTED)
    public AgentCronEntity findById(String id) {
        return em.find(AgentCronEntity.class, id);
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
                "UPDATE agentcron SET status=:running WHERE id=:id AND status=:active"
        ).setParameter("id", id)
         .setParameter("running", CronStatus.RUNNING.name())
         .setParameter("active", CronStatus.ACTIVE.name())
         .executeUpdate();
        return updated == 1;
    }

    /**
     * Releases a claimed job back to ACTIVE for next poll.
     * Used for throttle recovery — NEVER call markFailed for throttled jobs.
     */
    @Transactional(TxType.REQUIRED)
    public void releaseClaimToActive(String id) {
        em.createNativeQuery(
                "UPDATE agentcron SET status=:active WHERE id=:id AND status=:running"
        ).setParameter("id", id)
         .setParameter("active", CronStatus.ACTIVE.name())
         .setParameter("running", CronStatus.RUNNING.name())
         .executeUpdate();
    }

    /**
     * Recurring job: advance nextFireTime, keep ACTIVE.
     */
    @Transactional(TxType.REQUIRED)
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        // IMPORTANT: String passed explicitly because Hibernate 6 bulk JPQL bypasses @Converter autoApply for
        // bind parameters. SELECT queries use Instant directly (converter applies).
        em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :active, c.lastFireTime = :last, c.nextFireTime = :next, c.updatedAt = :now WHERE c.id = :id"
        ).setParameter("active", CronStatus.ACTIVE)
         .setParameter("last", lastFireTime)
         .setParameter("next", nextFireTime)
         .setParameter("now", Instant.now())
         .setParameter("id", id)
         .executeUpdate();
    }

    /**
     * One-shot job: terminal state, never rescheduled.
     */
    @Transactional(TxType.REQUIRED)
    public void markCompleted(String id, Instant lastFireTime) {
        // IMPORTANT: String passed explicitly because Hibernate 6 bulk JPQL bypasses @Converter autoApply for
        // bind parameters. SELECT queries use Instant directly (converter applies).
        em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :completed, c.lastFireTime = :last, c.updatedAt = :now WHERE c.id = :id"
        ).setParameter("completed", CronStatus.COMPLETED)
         .setParameter("last", lastFireTime)
         .setParameter("now", Instant.now())
         .setParameter("id", id)
         .executeUpdate();
    }

    /**
     * HITL pause: prevents re-fire loop by moving to a dedicated status.
     */
    @Transactional(TxType.REQUIRED)
    public void markAwaitingApproval(String id) {
        // IMPORTANT: String passed explicitly because Hibernate 6 bulk JPQL bypasses @Converter autoApply for
        // bind parameters. SELECT queries use Instant directly (converter applies).
        em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :awaiting, c.updatedAt = :now WHERE c.id = :id"
        ).setParameter("awaiting", CronStatus.AWAITING_APPROVAL)
         .setParameter("now", Instant.now())
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
        // IMPORTANT: String passed explicitly because Hibernate 6 bulk JPQL bypasses @Converter autoApply for
        // bind parameters. SELECT queries use Instant directly (converter applies).
        em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :failed, c.errorReason = :err, c.updatedAt = :now WHERE c.id = :id"
        ).setParameter("failed", CronStatus.FAILED)
         .setParameter("err", errorReason)
         .setParameter("now", Instant.now())
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
        Instant now = Instant.now();
        for (AgentCronEntity job : skippedJobs) {
            // IMPORTANT: String passed explicitly because Hibernate 6 bulk JPQL bypasses @Converter autoApply for
            // bind parameters. SELECT queries use Instant directly (converter applies).
            em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :skipped, c.nextFireTime = :next, c.updatedAt = :now WHERE c.id = :id"
             ).setParameter("skipped", CronStatus.SKIPPED)
              .setParameter("next", job.nextFireTime)
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
        // IMPORTANT: String passed explicitly because Hibernate 6 bulk JPQL bypasses @Converter autoApply for
        // bind parameters. SELECT queries use Instant directly (converter applies).
        em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :active " +
                "WHERE c.status = :running AND " +
                "(c.lastFireTime <= :threshold OR (c.lastFireTime IS NULL AND c.createdAt <= :threshold))"
        ).setParameter("active", CronStatus.ACTIVE)
         .setParameter("running", CronStatus.RUNNING)
         .setParameter("threshold", threshold)
         .executeUpdate();
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
