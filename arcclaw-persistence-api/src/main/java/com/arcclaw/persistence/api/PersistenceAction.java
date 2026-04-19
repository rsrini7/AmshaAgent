// arcclaw-persistence-api/src/main/java/com/arcclaw/persistence/api/PersistenceAction.java

package com.arcclaw.persistence.api;

/**
 * Replaces Consumer<EntityManager> in PersistenceAdapterPort.
 * Decoupled from JPA context types to satisfy Tier 3 invariants.
 */
@FunctionalInterface
public interface PersistenceAction {
    void execute();
}
