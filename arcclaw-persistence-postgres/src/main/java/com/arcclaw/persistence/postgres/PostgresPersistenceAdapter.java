package com.arcclaw.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.PersistenceAction;
import com.arcclaw.persistence.api.PersistenceAdapterPort;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of {@link PersistenceAdapterPort}.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresPersistenceAdapter implements PersistenceAdapterPort {

    @Inject
    EntityManager em;

    @Inject
    PostgresPersistenceAdapter self;

    @Override
    public void execute(String sessionId, String operationName, PersistenceAction action) {
        self.persistSynchronously(action);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void persistSynchronously(PersistenceAction action) {
        em.clear();
        action.execute();
    }
}
