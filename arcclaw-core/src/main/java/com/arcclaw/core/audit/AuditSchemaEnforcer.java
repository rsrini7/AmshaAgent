package com.arcclaw.core.audit;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.annotation.Priority;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Enforces WORM (Write-Once-Read-Many) compliance at the database level using SQLite triggers.
 * Prevent updates and deletes on the audit_event table.
 * 
 * Runs after Hibernate schema generation (@Priority APPLICATION + 100).
 */
@ApplicationScoped
public class AuditSchemaEnforcer {

    @Inject
    EntityManager em;

    @Inject
    org.jboss.logging.Logger log;

    @Transactional
    public void onStart(
        @Observes @Priority(Interceptor.Priority.APPLICATION + 100)
        StartupEvent event) {

        // WORM triggers — already present
        em.createNativeQuery("""
            CREATE TRIGGER IF NOT EXISTS prevent_audit_update
            BEFORE UPDATE ON audit_event
            BEGIN
                SELECT RAISE(ABORT, 'audit_event rows are immutable');
            END
            """).executeUpdate();

        em.createNativeQuery("""
            CREATE TRIGGER IF NOT EXISTS prevent_audit_delete
            BEFORE DELETE ON audit_event
            BEGIN
                SELECT RAISE(ABORT, 'audit_event rows are immutable');
            END
            """).executeUpdate();

        // indexes with IF NOT EXISTS — idempotent on every restart
        em.createNativeQuery(
            "CREATE INDEX IF NOT EXISTS idx_audit_session ON audit_event (sessionId)"
        ).executeUpdate();

        em.createNativeQuery(
            "CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_event (timestamp)"
        ).executeUpdate();

        em.createNativeQuery(
            "CREATE INDEX IF NOT EXISTS idx_audit_userId ON audit_event (userId)"
        ).executeUpdate();

        log.info("AuditSchemaEnforcer: WORM triggers and indexes verified.");
    }
}
