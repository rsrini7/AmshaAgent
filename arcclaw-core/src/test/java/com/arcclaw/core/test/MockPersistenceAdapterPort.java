package com.arcclaw.core.test;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.arcclaw.persistence.api.PersistenceAction;
import com.arcclaw.persistence.api.PersistenceAdapterPort;
import com.arcclaw.persistence.api.PersistenceContext;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockPersistenceAdapterPort implements PersistenceAdapterPort {

    @Inject
    EntityManager em;

    @Override
    public void execute(String sessionId, String operationName, PersistenceAction action) {
        action.execute(new PersistenceContext() {
            @Override public void persist(Object entity) { em.persist(entity); }
            @Override public void merge(Object entity)   { em.merge(entity); }
            @Override public void remove(Object entity)  { em.remove(entity); }
            @Override public <T> T find(Class<T> type, Object id) { return em.find(type, id); }
        });
    }
}
