package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.persistence.api.PersistenceAction;
import com.amsha.persistence.api.PersistenceAdapterPort;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqlitePersistenceAdapter implements PersistenceAdapterPort {

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 100;

    @Inject
    JdbiRetryWrapper retryWrapper;

    @Override
    public void execute(String sessionId, String operationName, PersistenceAction action) {
        retryWrapper.withRetryVoid(operationName, MAX_ATTEMPTS, INITIAL_BACKOFF_MS, action::execute);
    }
}
