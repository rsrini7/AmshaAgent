package com.arcclaw.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.PersistenceAction;
import com.arcclaw.persistence.api.PersistenceAdapterPort;
import io.quarkus.arc.Unremovable;

/**
 * SQLite implementation of {@link PersistenceAdapterPort}.
 * Wraps database actions with SQLite-specific retry logic via {@link SqliteRetryTemplate}.
 */
@ApplicationScoped
@Unremovable
public class SqlitePersistenceAdapter implements PersistenceAdapterPort {


    @Inject
    EntityManager em;

    @Inject
    SqliteRetryTemplate retryTemplate;

    @Inject
    SqlitePersistenceAdapter self;

    @Override
    public void execute(String sessionId, String operationName, PersistenceAction action) {
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

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void persistSynchronously(PersistenceAction action) {
        em.clear();
        action.execute();
    }
}
