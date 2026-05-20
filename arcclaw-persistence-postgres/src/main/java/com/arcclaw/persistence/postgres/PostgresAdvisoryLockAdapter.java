package com.arcclaw.persistence.postgres;

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
 * PostgreSQL implementation of AdvisoryLockPort.
 * Uses the advisory_lock table and atomic UPSERT (ON CONFLICT DO NOTHING) behavior.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresAdvisoryLockAdapter implements AdvisoryLockPort {

    @Inject
    EntityManager em;

    @Inject
    PostgresAdvisoryLockAdapter self;

    private final String ownerId = UUID.randomUUID().toString();

    @Override
    public boolean tryLock(String lockKey, int timeoutSeconds) {
        try {
            return self.doTryLock(lockKey, timeoutSeconds);
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire advisory lock: " + lockKey, e);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean doTryLock(String lockKey, int timeoutSeconds) {
        Instant now = Instant.now();
        
        // 1. Clean up stale lock if it exists
        em.createQuery("DELETE FROM AdvisoryLockEntity l WHERE l.lockKey = :key AND l.expiresAt < :now")
          .setParameter("key", lockKey)
          .setParameter("now", now)
          .executeUpdate();
        
        // 2. Attempt to insert fresh lock using ON CONFLICT DO NOTHING to avoid PK violation exceptions
        int rows = em.createNativeQuery(
                "INSERT INTO advisory_lock (lock_key, owner_id, expires_at) VALUES (?, ?, ?) ON CONFLICT (lock_key) DO NOTHING")
          .setParameter(1, lockKey)
          .setParameter(2, ownerId)
          .setParameter(3, now.plusSeconds(timeoutSeconds))
          .executeUpdate();

        return rows > 0;
    }

    @Override
    public void unlock(String lockKey) {
        try {
            self.doUnlock(lockKey);
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

    @Override
    public boolean isLocked(String lockKey) {
        try {
            return self.doIsLocked(lockKey);
        } catch (Exception e) {
            return false;
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
