// arcclaw-persistence-api/src/main/java/com/arcclaw/persistence/api/PersistenceAction.java

package com.arcclaw.persistence.api;

/**
 * Replaces Consumer<EntityManager> in PersistenceAdapterPort.
 * Core code constructs PersistenceAction lambdas without importing EntityManager.
 */
@FunctionalInterface
public interface PersistenceAction {
    void execute(PersistenceContext ctx);
}
