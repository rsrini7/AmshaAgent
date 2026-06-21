// persistence-api/src/main/java/com/amsha/persistence/api/PersistenceAction.java

package com.amsha.persistence.api;

/**
 * A unit-of-work action executed within a persistence context.
 * Decoupled from JPA context types to satisfy Tier 3 invariants.
 */
@FunctionalInterface
public interface PersistenceAction {
    void execute();
}
