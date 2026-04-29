package com.arcclaw.persistence.sqlite;

import java.util.List;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.quarkus.runtime.StartupEvent;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Creates the {@code agent_cron} table and indexes at startup.
 * <p>
 * Runs AFTER Hibernate schema generation ({@code APPLICATION + 100}).
 * Uses {@code CREATE TABLE IF NOT EXISTS} and safe {@code ALTER TABLE}
 * migrations wrapped in try-catch for idempotent restarts.
 * <p>
 * DDL lives here - NOT in {@code AuditSchemaEnforcer} - to keep
 * cron concerns isolated from audit/WORM logic.
 */
@ApplicationScoped
public class CronSchemaEnforcer {

    private static final Logger log = Logger.getLogger(CronSchemaEnforcer.class);

    @Inject
    EntityManager em;

    @Transactional
    public void onStart(
            @Observes @Priority(Interceptor.Priority.APPLICATION + 100)
            StartupEvent event) {

        // 1. Core table
        em.createNativeQuery("""
                CREATE TABLE IF NOT EXISTS agent_cron (
                    id                  TEXT PRIMARY KEY,
                    sessionId           TEXT NOT NULL,
                    userId              TEXT NOT NULL,
                    chatId              TEXT NOT NULL,
                    channelType         TEXT,
                    channelPlatformId   TEXT,
                    cronExpression      TEXT NOT NULL,
                    timezone            TEXT NOT NULL DEFAULT 'UTC',
                    runOnce             INTEGER NOT NULL DEFAULT 0,
                    goal                TEXT NOT NULL,
                    nextFireTime        TEXT NOT NULL,
                    lastFireTime        TEXT,
                    status              TEXT NOT NULL DEFAULT 'ACTIVE',
                    executionRole       TEXT,
                    errorReason         TEXT,
                    lastDeliveryId      TEXT,
                    createdAt           TEXT NOT NULL,
                    updatedAt           TEXT
                )
                """).executeUpdate();

        // 2. Indexes - partial index on active rows for fast poll queries
        em.createNativeQuery(
                "CREATE INDEX IF NOT EXISTS idx_cron_nextfire ON agent_cron(nextFireTime) WHERE status='ACTIVE'"
        ).executeUpdate();

        em.createNativeQuery(
                "CREATE INDEX IF NOT EXISTS idx_cron_status ON agent_cron(status)"
        ).executeUpdate();

        em.createNativeQuery(
                "CREATE INDEX IF NOT EXISTS idx_cron_userid ON agent_cron(userId)"
        ).executeUpdate();

        // 3. Schema migrations - safe ALTER TABLE for upgrades from older schemas
        // Check if columns exist before trying to add them to avoid SQLITE_ERROR log pollution
        if (!hasColumn("agent_cron", "runOnce")) {
            try {
                em.createNativeQuery(
                        "ALTER TABLE agent_cron ADD COLUMN runOnce INTEGER NOT NULL DEFAULT 0"
                ).executeUpdate();
            } catch (Exception e) {
                log.warnf("Migration failed for agent_cron: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }

        if (!hasColumn("agent_cron", "lastDeliveryId")) {
            try {
                em.createNativeQuery(
                        "ALTER TABLE agent_cron ADD COLUMN lastDeliveryId TEXT"
                ).executeUpdate();
            } catch (Exception e) {
                log.warnf("Migration failed for agent_cron: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }

        if (!hasColumn("agent_cron", "channelType")) {
            try {
                em.createNativeQuery(
                        "ALTER TABLE agent_cron ADD COLUMN channelType TEXT"
                ).executeUpdate();
            } catch (Exception e) {
                log.warnf("Migration failed for agent_cron: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }

        if (!hasColumn("agent_cron", "channelPlatformId")) {
            try {
                em.createNativeQuery(
                        "ALTER TABLE agent_cron ADD COLUMN channelPlatformId TEXT"
                ).executeUpdate();
            } catch (Exception e) {
                log.warnf("Migration failed for agent_cron: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }

        if (!hasColumn("agent_cron", "executionRole")) {
            try {
                em.createNativeQuery(
                        "ALTER TABLE agent_cron ADD COLUMN executionRole TEXT"
                ).executeUpdate();
            } catch (Exception e) {
                log.warnf("Migration failed for agent_cron: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }

        if (!hasColumn("agent_cron", "updatedAt")) {
            try {
                em.createNativeQuery(
                        "ALTER TABLE agent_cron ADD COLUMN updatedAt TEXT"
                ).executeUpdate();
            } catch (Exception e) {
                log.warnf("Migration failed for agent_cron: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }

        log.info("CronSchemaEnforcer: agent_cron table, indexes, and migrations verified.");
    }

    /**
     * Checks if a column exists in a table using SQLite's pragma_table_info.
     */
    private boolean hasColumn(String tableName, String columnName) {
        try {
            // Using a more robust check that works across different SQLite/JDBC versions
            @SuppressWarnings("unchecked")
            List<Object> results = em.createNativeQuery(
                    "PRAGMA table_info(" + tableName + ")"
            ).getResultList();

            for (Object row : results) {
                if (row instanceof Object[] columns) {
                    // PRAGMA table_info returns: [cid, name, type, notnull, dflt_value, pk]
                    if (columns.length > 1 && columnName.equalsIgnoreCase(String.valueOf(columns[1]))) {
                        return true;
                    }
                } else if (row instanceof String name) {
                    // Some drivers might return just the name if the query is different
                    if (columnName.equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debugf("Failed to check column existence for %s.%s: %s", tableName, columnName, e.getMessage());
        }
        return false;
    }
}
