package com.arcclaw.core.test;

import java.util.function.Consumer;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.arcclaw.persistence.api.PersistenceAdapterPort;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockPersistenceAdapterPort implements PersistenceAdapterPort {

    @Inject
    EntityManager em;

    @Override
    public void execute(String sessionId, String operationName, Consumer<EntityManager> action) {
        action.accept(em);
    }
}
