package com.arcclaw.persistence.sqlite;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Ensures SQLite WAL mode is enabled for concurrent read/write and
 * re-attaches the FTS database on every fresh connection if needed.
 * <p>
 * Entity table DDL is managed by Flyway (V1__baseline.sql).
 * Logic moved to the persistence-sqlite module to isolate SQLite-specific
 * boot configuration from the core domain.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteWalConfigurer {

    private static final Logger log = Logger.getLogger(SqliteWalConfigurer.class);

    @Inject DataSource dataSource;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url", defaultValue = "jdbc:sqlite:./data/arcclaw-meta.db")
    String jdbcUrl;

    /**
     * Boot-time configuration - fires BEFORE RagSchemaInitializer.
     */
    @Priority(10)
    void onStartup(@Observes @Priority(10) StartupEvent event) {
        log.info("SQLite: Configuring WAL mode and FTS cross-database ATTACH...");
        
        File ftsDb = ftsDbPath();

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
            try {
                stmt.execute(attachSql);
                log.infof("SQLite: FTS database attached from %s", ftsDb.getAbsolutePath());
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().contains("already in use")) {
                    log.debug("SQLite: FTS database already attached by pool interceptor");
                } else {
                    throw e;
                }
            }

        } catch (SQLException e) {
            log.error("SQLite: Failed to configure WAL/ATTACH on startup", e);
        }
    }

    private File ftsDbPath() {
        String path = jdbcUrl.replace("jdbc:sqlite:", "");
        int qIndex = path.indexOf('?');
        if (qIndex > 0) path = path.substring(0, qIndex);
        int sep = path.lastIndexOf('/');
        String dir = sep > 0 ? path.substring(0, sep) : ".";
        return Path.of(dir, "arcclaw-fts.db").toFile();
    }
}
