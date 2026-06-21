package com.arcclaw.persistence.postgres;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.util.Locale;

import com.arcclaw.persistence.api.AuditSchemaPort;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;

/**
 * Enforces WORM (Write-Once-Read-Many) compliance at the database level using PostgreSQL triggers.
 * Prevent updates and deletes on the audit_event table.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresAuditSchemaEnforcer implements AuditSchemaPort {

    private final Jdbi jdbi;
    private final Logger log;

    @Inject
    public PostgresAuditSchemaEnforcer(Jdbi jdbi, Logger log) {
        this.jdbi = jdbi;
        this.log = log;
    }

    protected PostgresAuditSchemaEnforcer() {
        this(null, null);
    }

    @Override
    public void enforceWormConstraints() {
        log.info("PostgresAuditSchemaEnforcer: Enforcing WORM constraints on audit_event table...");
        try {
            jdbi.useHandle(handle -> {
                // 1. Create a function that throws an error on modification
                handle.execute("""
                    CREATE OR REPLACE FUNCTION prevent_audit_modification()
                    RETURNS TRIGGER AS $$
                    BEGIN
                        RAISE EXCEPTION 'audit_event rows are immutable (WORM policy violation)';
                    END;
                    $$ LANGUAGE plpgsql;
                    """);

                // 2. Create triggers to call the function BEFORE UPDATE or DELETE
                handle.execute("""
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
                    """);
            });
            log.info("PostgresAuditSchemaEnforcer: WORM triggers verified.");
        } catch (Exception e) {
            log.error("PostgresAuditSchemaEnforcer: Failed to enforce WORM triggers", e);
        }
    }

    public void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 100) StartupEvent event) {

        try {
            // Verify if we are running on Postgres
            String dbProduct = jdbi.withHandle(handle ->
                    handle.createQuery("SELECT version()").mapTo(String.class).one());
            if (dbProduct != null && dbProduct.toLowerCase(Locale.ROOT).contains("postgres")) {
                enforceWormConstraints();

                // Add indexes
                jdbi.useHandle(handle -> {
                    handle.execute("CREATE INDEX IF NOT EXISTS idx_audit_session_pg ON audit_event (sessionId)");
                    handle.execute("CREATE INDEX IF NOT EXISTS idx_audit_timestamp_pg ON audit_event (timestamp)");
                });
                log.info("PostgresAuditSchemaEnforcer: Indexes verified.");
            }
        } catch (Exception e) {
            log.debug("PostgresAuditSchemaEnforcer: Not running on PostgreSQL or table not yet present. Skipping.");
        }
    }
}
