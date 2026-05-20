package com.arcclaw.persistence.postgres;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.AuditSchemaPort;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

/**
 * Enforces WORM (Write-Once-Read-Many) compliance at the database level using PostgreSQL triggers.
 * Prevent updates and deletes on the audit_event table.
 * 
 * Runs after Hibernate schema generation (@Priority APPLICATION + 100).
 */
@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresAuditSchemaEnforcer implements AuditSchemaPort {

    @Inject
    EntityManager em;

    @Inject
    Logger log;

    @Override
    @Transactional
    public void enforceWormConstraints() {
        log.info("PostgresAuditSchemaEnforcer: Enforcing WORM constraints on audit_event table...");

        // 1. Create a function that throws an error on modification
        em.createNativeQuery("""
            CREATE OR REPLACE FUNCTION prevent_audit_modification()
            RETURNS TRIGGER AS $$
            BEGIN
                RAISE EXCEPTION 'audit_event rows are immutable (WORM policy violation)';
            END;
            $$ LANGUAGE plpgsql;
            """).executeUpdate();

        // 2. Create triggers to call the function BEFORE UPDATE or DELETE
        em.createNativeQuery("""
            DO $$
            BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_prevent_audit_update') THEN
                    CREATE TRIGGER trg_prevent_audit_update
                    BEFORE UPDATE ON audit_event
                    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();
                END IF;
                
                IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_prevent_audit_delete') THEN
                    CREATE TRIGGER trg_prevent_audit_delete
                    BEFORE DELETE ON audit_event
                    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();
                END IF;
            END $$;
            """).executeUpdate();
            
        log.info("PostgresAuditSchemaEnforcer: WORM triggers verified.");
    }

    @Transactional
    public void onStart(
        @Observes @Priority(Interceptor.Priority.APPLICATION + 100)
        StartupEvent event) {
        
        // This bean only runs if Postgres is the active persistence provider.
        // In this project, we currently use SQLite by default.
        // Future: add @IfBuildProperty check when Postgres is fully integrated.
        
        try {
            // Verify if we are running on Postgres
            String dbProduct = (String) em.createNativeQuery("SELECT version()").getSingleResult();
            if (dbProduct != null && dbProduct.toLowerCase().contains("postgres")) {
                enforceWormConstraints();
                
                // Add indexes
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_audit_session_pg ON audit_event (sessionId)").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_audit_timestamp_pg ON audit_event (timestamp)").executeUpdate();
                log.info("PostgresAuditSchemaEnforcer: Indexes verified.");
            }
        } catch (Exception e) {
            log.debug("PostgresAuditSchemaEnforcer: Not running on PostgreSQL or table not yet present. Skipping.");
        }
    }
}
