package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

import com.amsha.persistence.api.AdvisoryLockPort;
import com.amsha.persistence.postgres.jdbi.LockJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of AdvisoryLockPort using JDBI.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresAdvisoryLockAdapter implements AdvisoryLockPort {

    private static final Logger log = Logger.getLogger(PostgresAdvisoryLockAdapter.class);

    private final Jdbi jdbi;
    private final String ownerId = UUID.randomUUID().toString();

    @Inject
    public PostgresAdvisoryLockAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresAdvisoryLockAdapter() {
        this(null);
    }

    @Override
    public boolean tryLock(String lockKey, int timeoutSeconds) {
        log.tracef("tryLock key=%s timeout=%d", lockKey, timeoutSeconds);
        try {
            return jdbi.inTransaction(handle -> {
                var dao = handle.attach(LockJdbiDao.class);
                Instant now = Instant.now();
                dao.deleteExpired(lockKey, now);
                int rows = dao.tryInsert(lockKey, ownerId, now.plusSeconds(timeoutSeconds));
                return rows > 0;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire advisory lock: " + lockKey, e);
        }
    }

    @Override
    public void unlock(String lockKey) {
        log.tracef("unlock key=%s", lockKey);
        try {
            jdbi.useExtension(LockJdbiDao.class, dao -> dao.deleteByOwner(lockKey, ownerId));
        } catch (Exception e) {
            throw new RuntimeException("Failed to release advisory lock: " + lockKey, e);
        }
    }

    @Override
    public boolean isLocked(String lockKey) {
        try {
            return jdbi.withExtension(LockJdbiDao.class, dao -> dao.countActive(lockKey, Instant.now()) > 0);
        } catch (Exception e) {
            return false;
        }
    }
}
