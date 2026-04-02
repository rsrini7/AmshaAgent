package com.arcclaw.core.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Plain JPA Repository for EncryptedSecret.
 * Bypasses Panache complexity to ensure reliability in multi-module build/test
 * environments.
 */
@ApplicationScoped
public class EncryptedSecretRepository {

    @Inject
    EntityManager em;

    public void persist(EncryptedSecret entity) {
        em.persist(entity);
    }

    public EncryptedSecret findByKey(String sessionId, String keyName) {
        EncryptedSecret.EncryptedSecretId id = new EncryptedSecret.EncryptedSecretId(sessionId, keyName);
        return em.find(EncryptedSecret.class, id);
    }

    public void deleteBySessionId(String sessionId) {
        em.createQuery("DELETE FROM EncryptedSecret s WHERE s.id.sessionId = :sessionId")
                .setParameter("sessionId", sessionId)
                .executeUpdate();
    }

    public void deleteByKey(String sessionId, String keyName) {
        EncryptedSecret entity = findByKey(sessionId, keyName);
        if (entity != null) {
            em.remove(entity);
        }
    }
}
