// arcclaw-persistence-api/src/main/java/com/arcclaw/persistence/api/AuditSchemaPort.java

package com.arcclaw.persistence.api;

/**
 * Adapter contract for enforcing WORM (write-once read-many) constraints on the
 * audit_event table. Each persistence adapter implements backend-specific DDL.
 * §5.23: DDL isolation - implementations live in adapter modules only.
 */
public interface AuditSchemaPort {
    /**
     * Enforce WORM constraints for the audit_event table.
     * Called once at startup by the adapter's @PostConstruct or Flyway callback.
     */
    void enforceWormConstraints();
}
