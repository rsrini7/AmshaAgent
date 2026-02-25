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

    @Transactional
    public void onStart(
            @Observes @Priority(Interceptor.Priority.APPLICATION + 100)
            StartupEvent event) {

        // Prevent modification of existing audit records
        em.createNativeQuery("""
            CREATE TRIGGER IF NOT EXISTS prevent_audit_update
            BEFORE UPDATE ON audit_event
            BEGIN
                SELECT RAISE(ABORT, 'audit_event rows are immutable');
            END
            """).executeUpdate();

        // Prevent deletion of existing audit records
        em.createNativeQuery("""
            CREATE TRIGGER IF NOT EXISTS prevent_audit_delete
            BEFORE DELETE ON audit_event
            BEGIN
                SELECT RAISE(ABORT, 'audit_event rows are immutable');
            END
            """).executeUpdate();
    }
}
