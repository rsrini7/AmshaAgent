package com.arcclaw.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.PersistenceAction;
import com.arcclaw.persistence.api.PersistenceAdapterPort;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of {@link PersistenceAdapterPort}.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresPersistenceAdapter implements PersistenceAdapterPort {

    private final Jdbi jdbi;

    @Inject
    public PostgresPersistenceAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresPersistenceAdapter() {
        this(null);
    }

    @Override
    public void execute(String sessionId, String operationName, PersistenceAction action) {
        jdbi.useTransaction(h -> action.execute());
    }
}
