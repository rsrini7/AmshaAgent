// arcclaw-persistence-api/src/main/java/com/arcclaw/persistence/api/PersistenceContext.java

package com.arcclaw.persistence.api;

/**
 * Minimal persistence surface exposed to arcclaw-core.
 * Hides the JPA EntityManager from all core code (§5.14.1).
 */
public interface PersistenceContext {
    void persist(Object entity);
    void merge(Object entity);
    void remove(Object entity);
    <T> T find(Class<T> type, Object id);
}
