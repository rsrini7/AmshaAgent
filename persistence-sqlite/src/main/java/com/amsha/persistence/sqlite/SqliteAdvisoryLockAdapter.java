package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

import com.amsha.common.util.TemporalUtils;
import com.amsha.persistence.api.AdvisoryLockPort;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.sqlite.jdbi.LockJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.SQLITE, enableIfMissing = true)
public class SqliteAdvisoryLockAdapter implements AdvisoryLockPort {

    private static final Logger log = Logger.getLogger(SqliteAdvisoryLockAdapter.class);

    private final Jdbi jdbi;
    private final JdbiRetryWrapper retryWrapper;
    private final String ownerId = UUID.randomUUID().toString();

    @Inject
    public SqliteAdvisoryLockAdapter(Jdbi jdbi, JdbiRetryWrapper retryWrapper) {
        this.jdbi = jdbi;
        this.retryWrapper = retryWrapper;
    }

    protected SqliteAdvisoryLockAdapter() {
        this(null, null);
    }

    @Override
    public boolean tryLock(String lockKey, int timeoutSeconds) {
        log.tracef("tryLock key=%s timeout=%d", lockKey, timeoutSeconds);
        return retryWrapper.withRetry(
                "try-lock-" + lockKey,
                3,
                50,
                () -> jdbi.inTransaction(handle -> {
                    var dao = handle.attach(LockJdbiDao.class);
                    String now = TemporalUtils.formatIsoLex(Instant.now());
                    dao.deleteExpired(lockKey, now);
                    int rows = dao.tryInsert(
                            lockKey,
                            ownerId,
                            TemporalUtils.formatIsoLex(Instant.now().plusSeconds(timeoutSeconds)));
                    return rows > 0;
                }));
    }

    @Override
    public void unlock(String lockKey) {
        log.tracef("unlock key=%s", lockKey);
        retryWrapper.withRetryVoid(
                "unlock-" + lockKey,
                3,
                50,
                () -> jdbi.useExtension(LockJdbiDao.class, dao -> dao.deleteByOwner(lockKey, ownerId)));
    }

    @Override
    public boolean isLocked(String lockKey) {
        return retryWrapper.withRetry(
                "is-locked-" + lockKey,
                3,
                50,
                () -> jdbi.withExtension(
                        LockJdbiDao.class,
                        dao -> dao.countActive(lockKey, TemporalUtils.formatIsoLex(Instant.now())) > 0));
    }

    @Override
    public boolean renew(String lockKey, int timeoutSeconds) {
        log.tracef("renew key=%s timeout=%d", lockKey, timeoutSeconds);
        return retryWrapper.withRetry(
                "renew-lock-" + lockKey,
                3,
                50,
                () -> jdbi.withExtension(
                        LockJdbiDao.class,
                        dao -> dao.renew(
                                        lockKey,
                                        ownerId,
                                        TemporalUtils.formatIsoLex(Instant.now().plusSeconds(timeoutSeconds)))
                                > 0));
    }
}
