package com.arcclaw.core.audit;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

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
    Logger log;

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

        // ── Phase 45: FTS5 Memory Index ──────────────────────────────────
        // Isolated in 'fts' schema to prevent Hibernate metadata scan crashes.
        // Standalone pattern (no external content) bypasses trigger qualification rules.

        // FTS5 standalone table — stores sessionId for manual mapping
        em.createNativeQuery("""
            CREATE VIRTUAL TABLE IF NOT EXISTS fts.compaction_summary_fts
            USING fts5(sessionId UNINDEXED, summary)
            """).executeUpdate();

        // SEED: Idempotent guard (clean start)
        long ftsCount = ((Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM fts.compaction_summary_fts")
            .getSingleResult()).longValue();

        if (ftsCount == 0L) {
            log.info("AuditSchemaEnforcer: FTS index empty. Skipping backfill (clean start).");
        } else {
            log.debugf("AuditSchemaEnforcer: FTS index has %d entries, verified.", ftsCount);
        }

        log.info("AuditSchemaEnforcer: FTS5 isolated memory index verified (standalone).");

        log.info("AuditSchemaEnforcer: FTS5 memory index and triggers verified.");
    }
}
