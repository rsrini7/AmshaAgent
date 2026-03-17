package com.arcclaw.core.persistence;

import io.agroal.api.AgroalPoolInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.sqlite.SQLiteConnection;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hardens SQLite FTS5 connection pooling by verifying/attaching the fts schema
 * on every connection acquisition. This prevents "no such table" or "database fts is already in use"
 * errors across different pool threads.
 */
@ApplicationScoped
public class FtsConnectionInterceptor implements AgroalPoolInterceptor {

    private static final Logger log = Logger.getLogger(FtsConnectionInterceptor.class);

    @ConfigProperty(name = "arcclaw.data-dir", defaultValue = "./data")
    String dataDir;

    @ConfigProperty(name = "arcclaw.rag.vec-extension-path", defaultValue = "./lib/vec0")
    String vecExtensionPath;

    private final AtomicBoolean vectorSearchEnabled = new AtomicBoolean(false);

    @Override
    public void onConnectionAcquire(Connection connection) {
        try {
            boolean attached = false;
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA database_list")) {
                while (rs.next()) {
                    if ("fts".equals(rs.getString("name"))) {
                        attached = true;
                        break;
                    }
                }
            }

            if (!attached) {
                String ftsPath = dataDir + "/arcclaw-fts.db";
                // Handle in-memory fallback if needed (useful for tests)
                if (ftsPath.contains(":memory:")) ftsPath = ":memory:";
                
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("ATTACH DATABASE '" + ftsPath + "' AS fts");
                }
            }

            // Step 2: Enable and load sqlite-vec extension
            if (!vectorSearchEnabled.get()) {
                try {
                    SQLiteConnection sqliteConn =
                        connection.unwrap(SQLiteConnection.class);
                    sqliteConn.getDatabase().enable_load_extension(true);

                    try (Statement vecStmt = connection.createStatement()) {
                        vecStmt.execute("SELECT load_extension('" + vecExtensionPath + "')");
                        vectorSearchEnabled.compareAndSet(false, true);
                        log.info("FtsConnectionInterceptor: sqlite-vec loaded successfully.");
                    }
                } catch (Exception e) {
                    log.warnf("FtsConnectionInterceptor: sqlite-vec unavailable — " +
                        "vector search degraded to FTS5-only: %s", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warnf("FtsConnectionInterceptor: Failed to verify/attach fts schema: %s", e.getMessage());
        }
    }

    public boolean isVectorSearchEnabled() {
        return vectorSearchEnabled.get();
    }
}
