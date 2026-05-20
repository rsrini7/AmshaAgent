package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.AdvisoryLockPort;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * SQLite implementation of {@link AdvisoryLockPort} (§T5-3).
 * Uses a dedicated table and atomic UPSERT-like behavior to simulate advisory locks.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteAdvisoryLockAdapter implements AdvisoryLockPort {

    @Inject
    EntityManager em;

    @Inject
    SqliteRetryTemplate retryTemplate;

    @Inject
    SqliteAdvisoryLockAdapter self;

    private final String ownerId = UUID.randomUUID().toString();

    @Override
    public boolean tryLock(String lockKey, int timeoutSeconds) {
        try {
            return retryTemplate.execute("try-lock-" + lockKey, 3, 50, () -> self.doTryLock(lockKey, timeoutSeconds));
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire advisory lock: " + lockKey, e);
        }
    }

    /**
     * REQUIRES_NEW is intentional (§T5-3). Advisory lock acquisition must commit
     * immediately and be visible to other threads/processes. It must NOT join 
     * an outer transaction, as lock visibility would then be deferred until 
     * the caller commits. Safe in SQLite because all callers (AgentCronScheduler, 
     * CheckpointResumeHandler) are non-transactional at their entry points.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doTryLock(String lockKey, int timeoutSeconds) {
        Instant now = Instant.now();
        
        // 1. Clean up stale lock if it exists
        // Lexicographical string comparison for SQLite §ISO-LEX consistency
        em.createQuery("DELETE FROM AdvisoryLockEntity l WHERE l.lockKey = :key AND l.expiresAt < :now")
          .setParameter("key", lockKey)
          .setParameter("now", now)
          .executeUpdate();
        
        // 2. Attempt to insert fresh lock using native 'OR IGNORE' to avoid noisy PK violation exceptions
        int rows = em.createNativeQuery(
                "INSERT OR IGNORE INTO advisory_lock (lock_key, owner_id, expires_at) VALUES (?, ?, ?)")
          .setParameter(1, lockKey)
          .setParameter(2, ownerId)
          .setParameter(3, now.plusSeconds(timeoutSeconds).toString()) // ISO string for SQLite
          .executeUpdate();

        return rows > 0;
    }

    @Override
    public void unlock(String lockKey) {
        try {
            retryTemplate.execute("unlock-" + lockKey, 3, 50, () -> {
                self.doUnlock(lockKey);
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to release advisory lock: " + lockKey, e);
        }
    }

    /**
     * REQUIRES_NEW is intentional (§T5-3). Lock release must commit immediately 
     * to allow other processes to acquire it, regardless of the caller's 
     * subsequent success or failure.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doUnlock(String lockKey) {
        em.createQuery("DELETE FROM AdvisoryLockEntity l WHERE l.lockKey = :key AND l.ownerId = :owner")
          .setParameter("key", lockKey)
          .setParameter("owner", ownerId)
          .executeUpdate();
    }

    @Override
    public boolean isLocked(String lockKey) {
        try {
            return retryTemplate.execute("is-locked-" + lockKey, 3, 50, () -> self.doIsLocked(lockKey));
        } catch (Exception e) {
            return false; // Safest fallback
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean doIsLocked(String lockKey) {
        Instant now = Instant.now();
        Long count = em.createQuery("SELECT COUNT(l) FROM AdvisoryLockEntity l WHERE l.lockKey = :key AND l.expiresAt > :now", Long.class)
          .setParameter("key", lockKey)
          .setParameter("now", now)
          .getSingleResult();
        return count != null && count > 0;
    }
}
