package com.arcclaw.core.scheduler;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Creates the {@code agentcron} table and indexes at startup.
 * <p>
 * Runs AFTER Hibernate schema generation ({@code APPLICATION + 100}).
 * Uses {@code CREATE TABLE IF NOT EXISTS} and safe {@code ALTER TABLE}
 * migrations wrapped in try-catch for idempotent restarts.
 * <p>
 * DDL lives here — NOT in {@code AuditSchemaEnforcer} — to keep
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
                CREATE TABLE IF NOT EXISTS agentcron (
                    id              TEXT PRIMARY KEY,
                    sessionId       TEXT NOT NULL,
                    userId          TEXT NOT NULL,
                    chatId          TEXT NOT NULL,
                    cronExpression  TEXT NOT NULL,
                    timezone        TEXT NOT NULL DEFAULT 'UTC',
                    runOnce         INTEGER NOT NULL DEFAULT 0,
                    goal            TEXT NOT NULL,
                    nextFireTime    TEXT NOT NULL,
                    lastFireTime    TEXT,
                    status          TEXT NOT NULL DEFAULT 'ACTIVE',
                    errorReason     TEXT,
                    lastDeliveryId  TEXT,
                    createdAt       TEXT NOT NULL
                )
                """).executeUpdate();

        // 2. Indexes — partial index on active rows for fast poll queries
        em.createNativeQuery(
                "CREATE INDEX IF NOT EXISTS idx_cron_nextfire ON agentcron(nextFireTime) WHERE status='ACTIVE'"
        ).executeUpdate();

        em.createNativeQuery(
                "CREATE INDEX IF NOT EXISTS idx_cron_status ON agentcron(status)"
        ).executeUpdate();

        em.createNativeQuery(
                "CREATE INDEX IF NOT EXISTS idx_cron_userid ON agentcron(userId)"
        ).executeUpdate();

        // 3. Schema migrations — safe ALTER TABLE for upgrades from older schemas
        try {
            em.createNativeQuery(
                    "ALTER TABLE agentcron ADD COLUMN runOnce INTEGER NOT NULL DEFAULT 0"
            ).executeUpdate();
        } catch (Exception ignored) {
            // Column already exists — expected on subsequent startups
        }

        try {
            em.createNativeQuery(
                    "ALTER TABLE agentcron ADD COLUMN lastDeliveryId TEXT"
            ).executeUpdate();
        } catch (Exception ignored) {
            // Column already exists — expected on subsequent startups
        }

        log.info("CronSchemaEnforcer: agentcron table, indexes, and migrations verified.");
    }
}
