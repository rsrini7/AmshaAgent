package com.arcclaw.persistence.api;

/**
 * Port for distributed advisory locks (§T5-3).
 * SQLite: single-writer constraint is the lock.
 * Postgres: pg_try_advisory_lock.
 */
public interface AdvisoryLockPort {

    /**
     * Attempts to acquire a global lock with the given key.
     * @param lockKey the lock identifier
     * @param timeoutSeconds how long to hold the lock (if not explicitly released)
     * @return true if acquired
     */
    boolean tryLock(String lockKey, int timeoutSeconds);

    /**
     * Releases the lock.
     */
    void unlock(String lockKey);
}
