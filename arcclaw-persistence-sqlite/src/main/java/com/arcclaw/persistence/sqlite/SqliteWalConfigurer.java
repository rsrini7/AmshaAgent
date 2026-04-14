package com.arcclaw.persistence.sqlite;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Ensures SQLite WAL mode is enabled for concurrent read/write and
 * re-attaches the FTS database on every fresh connection if needed.
 * <p>
 * Logic moved to the persistence-sqlite module to isolate SQLite-specific
 * boot configuration from the core domain.
 */
@ApplicationScoped
public class SqliteWalConfigurer {

    private static final Logger log = Logger.getLogger(SqliteWalConfigurer.class);

    @Inject DataSource dataSource;

    @ConfigProperty(name = "arcclaw.data-dir", defaultValue = ".")
    String dataDir;

    /**
     * Boot-time configuration — fires BEFORE RagSchemaInitializer.
     */
    void onStartup(@Observes @Priority(10) StartupEvent event) {
        log.info("SQLite: Configuring WAL mode and FTS cross-database ATTACH...");
        
        File ftsDb = new File(dataDir, "arcclaw-fts.db");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Enable WAL mode
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("PRAGMA busy_timeout=10000;");
            log.info("SQLite: WAL mode and 10s busyTimeout enabled.");

            // 2. Attach FTS database for sub-queries
            if (!ftsDb.getParentFile().exists()) {
                ftsDb.getParentFile().mkdirs();
            }

            String attachSql = String.format("ATTACH DATABASE '%s' AS fts;", 
                    ftsDb.getAbsolutePath().replace("\\", "/"));
            stmt.execute(attachSql);
            log.infof("SQLite: FTS database attached from %s", ftsDb.getAbsolutePath());
            
            try {
                stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS fts.compaction_summary_fts USING fts5(sessionId, summary)");
            } catch (Exception e) {
                log.warn("SQLite: Failed to create compaction_summary_fts table", e);
            }

        } catch (SQLException e) {
            log.error("SQLite: Failed to configure WAL/ATTACH on startup", e);
        }
    }
}
