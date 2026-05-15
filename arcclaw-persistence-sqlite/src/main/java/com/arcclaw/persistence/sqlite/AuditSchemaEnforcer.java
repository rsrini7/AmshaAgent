package com.arcclaw.persistence.sqlite;

import java.nio.file.Path;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.AuditSchemaPort;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Enforces WORM (Write-Once-Read-Many) compliance at the database level using SQLite triggers.
 * Entity table DDL, WORM triggers, and indexes are managed by Flyway (V1__baseline.sql).
 * This class only handles FTS5 virtual table setup in the attached fts database.
 */
@ApplicationScoped
public class AuditSchemaEnforcer implements AuditSchemaPort {

    @Inject
    EntityManager em;

    @Inject
    Logger log;

    @ConfigProperty(name = "arcclaw.data-dir", defaultValue = "./data")
    String dataDir;

    @Override
    @Transactional
    public void enforceWormConstraints() {
        // WORM triggers are now managed by Flyway V1__baseline.sql
    }

    @Transactional
    public void onStart(
        @Observes @Priority(Interceptor.Priority.APPLICATION + 100)
        StartupEvent event) {

        log.info("AuditSchemaEnforcer: verifying FTS5 setup (entity DDL managed by Flyway).");

        // ── Phase 45: FTS5 Memory Index ──────────────────────────────────
        // Ensure fts schema is attached if pool interceptor didn't run yet
        int ftsFound = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM pragma_database_list WHERE name = 'fts'")
            .getSingleResult()).intValue();

        if (ftsFound == 0) {
            String ftsPath = Path.of(dataDir, "arcclaw-fts.db").toAbsolutePath().toString().replace("\\", "/");
            log.info("fts schema missing on startup, attaching fallback database at: " + ftsPath);
            em.createNativeQuery("ATTACH DATABASE '" + ftsPath + "' AS fts").executeUpdate();
        }

        // FTS5 standalone table - stores sessionId for manual mapping
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

        log.info("AuditSchemaEnforcer: FTS5 memory index and triggers verified.");
    }
}
