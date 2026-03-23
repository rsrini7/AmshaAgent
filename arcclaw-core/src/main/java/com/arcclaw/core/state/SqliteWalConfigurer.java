package com.arcclaw.core.state;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Configures SQLite WAL mode and concurrency tuning at application startup.
 * <p>
 * Executes the following PRAGMAs to prevent "database is locked" errors
 * during concurrent agent loop execution:
 * <ul>
 * <li>{@code PRAGMA journal_mode=WAL} — Write-Ahead Logging for concurrent
 * reads</li>
 * <li>{@code PRAGMA synchronous=NORMAL} — balanced durability/performance</li>
 * <li>{@code PRAGMA busy_timeout=10000} — 10s retry on lock contention</li>
 * </ul>
 */
@ApplicationScoped
public class SqliteWalConfigurer {

    private static final Logger log = Logger.getLogger(SqliteWalConfigurer.class);

    @Inject
    AgroalDataSource dataSource;

    @ConfigProperty(name = "arcclaw.data-dir", defaultValue = "./data")
    String dataDir;

    void onStartup(@Observes StartupEvent event) {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA busy_timeout=10000");

            // Isolated FTS schema attachment
            String ftsPath;
            if (":memory:".equals(dataDir)) {
                ftsPath = ":memory:";
            } else {
                ftsPath = Path.of(dataDir, "arcclaw-fts.db").toAbsolutePath().toString();
            }
            try {
                stmt.execute("ATTACH DATABASE '" + ftsPath + "' AS fts");
                log.info("FTS database attached: " + ftsPath);
            } catch (SQLException e) {
                if (e.getMessage().contains("already in use")) {
                    log.debug("FTS database already attached");
                } else {
                    throw e;
                }
            }

            log.info("SQLite WAL mode configured: journal_mode=WAL, synchronous=NORMAL, busy_timeout=10000");
        } catch (SQLException e) {
            log.error("Failed to configure SQLite WAL mode — concurrent operations may encounter lock errors", e);
        }
    }
}
