package com.arcclaw.core.test;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import com.arcclaw.persistence.api.PersistenceAction;
import com.arcclaw.persistence.api.PersistenceAdapterPort;

/**
 * Mock persistence adapter for unit testing arcclaw-core without a real database.
 * Architecture Note (§5.14.1): This implementation is strictly JPA-free.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class MockPersistenceAdapterPort implements PersistenceAdapterPort {

    @Override
    public void execute(String sessionId, String operationName, PersistenceAction action) {
        // Just execute the action synchronously. 
        // In unit tests, the action lambdas typically interact with other mocked repositories.
        action.execute();
    }
}
