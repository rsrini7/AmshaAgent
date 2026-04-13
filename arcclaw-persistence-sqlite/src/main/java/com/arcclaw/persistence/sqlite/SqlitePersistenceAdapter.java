package com.arcclaw.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.PersistenceAdapterPort;
import org.jboss.logging.Logger;

/**
 * SQLite implementation of {@link PersistenceAdapterPort}.
 * Wraps database actions with SQLite-specific retry logic via {@link SqliteRetryTemplate}.
 */
@ApplicationScoped
public class SqlitePersistenceAdapter implements PersistenceAdapterPort {

    private static final Logger log = Logger.getLogger(SqlitePersistenceAdapter.class);

    @Inject
    SqliteRetryTemplate retryTemplate;

    @Inject
    SqlitePersistenceAdapter self;

    @Override
    public void execute(String sessionId, String operationName, Runnable action) {
        try {
            retryTemplate.execute(operationName, 5, 50, () -> {
                self.persistSynchronously(action);
                return null;
            });
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Persistence operation failed after retries: " + operationName, e);
        }
    }

    /**
     * Executes the action in a fresh transaction.
     * Includes a specific retry loop for HMAC boot-window races (unique to audit security).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void persistSynchronously(Runnable action) {
        int hmacRetry = 0;
        while (true) {
            try {
                action.run();
                return;
            } catch (IllegalStateException e) {
                // Handle HMAC boot race — wait for Vault keys to be available
                if (e.getMessage() != null && e.getMessage().contains("HMAC key unavailable") && hmacRetry < 3) {
                    hmacRetry++;
                    log.warnf("Audit HMAC key not yet available, retry %d/3 in 100ms (no SQLite lock held)", hmacRetry);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
        }
    }
}
