package com.arcclaw.persistence.sqlite;

import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.PersistenceAdapterPort;

/**
 * SQLite implementation of {@link PersistenceAdapterPort}.
 * Wraps database actions with SQLite-specific retry logic via {@link SqliteRetryTemplate}.
 */
@ApplicationScoped
public class SqlitePersistenceAdapter implements PersistenceAdapterPort {


    @Inject
    EntityManager em;

    @Inject
    SqliteRetryTemplate retryTemplate;

    @Inject
    SqlitePersistenceAdapter self;

    @Override
    public void execute(String sessionId, String operationName, Consumer<EntityManager> action) {
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
    public void persistSynchronously(Consumer<EntityManager> action) {
        em.clear();
        action.accept(em);
    }
}
