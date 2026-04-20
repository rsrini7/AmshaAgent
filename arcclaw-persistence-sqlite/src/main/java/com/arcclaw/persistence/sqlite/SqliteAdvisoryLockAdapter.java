package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.AdvisoryLockEntity;
import com.arcclaw.persistence.api.AdvisoryLockPort;
import io.quarkus.arc.Unremovable;

/**
 * SQLite implementation of {@link AdvisoryLockPort} (§T5-3).
 * Uses a dedicated table and atomic UPSERT-like behavior to simulate advisory locks.
 */
@ApplicationScoped
@Unremovable
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

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doTryLock(String lockKey, int timeoutSeconds) {
        Instant now = Instant.now();
        
        // 1. Clean up stale lock if it exists
        // Lexicographical string comparison for SQLite §ISO-LEX consistency
        em.createQuery("DELETE FROM AdvisoryLockEntity l WHERE l.lockKey = :key AND l.expiresAt < :now")
          .setParameter("key", lockKey)
          .setParameter("now", now)
          .executeUpdate();
        
        // 2. Attempt to insert fresh lock
        try {
            AdvisoryLockEntity lock = new AdvisoryLockEntity(lockKey, ownerId, now.plusSeconds(timeoutSeconds));
            em.persist(lock);
            em.flush();
            return true;
        } catch (Exception e) {
            // Already locked by someone else
            return false;
        }
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

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doUnlock(String lockKey) {
        em.createQuery("DELETE FROM AdvisoryLockEntity l WHERE l.lockKey = :key AND l.ownerId = :owner")
          .setParameter("key", lockKey)
          .setParameter("owner", ownerId)
          .executeUpdate();
    }
}
