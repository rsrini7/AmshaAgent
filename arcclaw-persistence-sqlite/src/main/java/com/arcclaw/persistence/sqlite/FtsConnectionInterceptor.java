package com.arcclaw.persistence.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Singleton;

import io.agroal.api.AgroalPoolInterceptor;
import io.quarkus.arc.Arc;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.sqlite.SQLiteConnection;

/**
 * Hardens SQLite FTS5 connection pooling by verifying/attaching the fts schema
 * on every connection acquisition. This prevents "no such table" or "database
 * fts is already in use" errors across different pool threads.
 *
 * <p><strong>sqlite-vec loading contract:</strong>
 * <ul>
 *   <li>SQLite extensions are per-connection (per sqlite3* handle).</li>
 *   <li>Agroal REUSES physical connections — onConnectionAcquire fires on
 *       every logical borrow, including re-borrows of the same sqlite3* handle.</li>
 *   <li>Calling load_extension twice on the same sqlite3* handle fails with
 *       "error during initialization:" because the module is already registered.</li>
 *   <li>Fix: check pragma_module_list on each connection before loading —
 *       skip if vec0 is already registered on that specific handle.</li>
 * </ul>
 * 
 * Encapsulated inside arcclaw-persistence-sqlite adapter module.
 * Registered with Agroal via Quarkus configuration.
 */
@Singleton
public class FtsConnectionInterceptor implements AgroalPoolInterceptor {

    private static final Logger log = Logger.getLogger(FtsConnectionInterceptor.class);

    @ConfigProperty(name = "arcclaw.data-dir", defaultValue = "./data")
    String dataDir;

    @ConfigProperty(name = "arcclaw.rag.vec-extension-path", defaultValue = "./lib/vec0")
    String vecExtensionPath;

    /** True once any connection has successfully loaded the sqlite-vec module. */
    private final AtomicBoolean vectorSearchEnabled = new AtomicBoolean(false);

    /** Guards the "vec0 unavailable" WARN so it fires at most once. */
    private final AtomicBoolean warnedUnavailable = new AtomicBoolean(false);

    @Override
    public void onConnectionAcquire(Connection connection) {
        if (Arc.container() == null) {
            return; // Shutdown in progress
        }
        try {
            // ----------------------------------------------------------------
            // Step 1: Attach fts schema if not already attached on this
            //         connection. ATTACH is also per-connection.
            // ----------------------------------------------------------------
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
                String ftsPath;
                try {
                    Files.createDirectories(Path.of(dataDir));
                } catch (IOException e) {
                    log.warn("Failed to create data directory for FTS: " + dataDir, e);
                }
                ftsPath = Path.of(dataDir, "arcclaw-fts.db").toAbsolutePath().toString().replace("\\", "/");

                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("ATTACH DATABASE '" + ftsPath + "' AS fts");
                }
            }

            // ----------------------------------------------------------------
            // Step 2: Load sqlite-vec on this connection — but only if vec0
            //         is not already registered on this specific sqlite3* handle.
            //
            //         Agroal reuses physical connections. Calling load_extension
            //         twice on the same sqlite3* handle fails with:
            //         "error during initialization: " (empty msg = already registered).
            //
            //         pragma_module_list is the per-connection check — it reflects
            //         exactly which modules are registered on THIS handle.
            // ----------------------------------------------------------------
            try {
                boolean alreadyLoaded = false;
                try (Statement checkStmt = connection.createStatement();
                     ResultSet moduleRs = checkStmt.executeQuery(
                             "SELECT name FROM pragma_module_list WHERE name='vec0'")) {
                    if (moduleRs.next()) {
                        alreadyLoaded = true;
                    }
                }

                if (!alreadyLoaded) {
                    SQLiteConnection sqliteConn = connection.unwrap(SQLiteConnection.class);
                    sqliteConn.getDatabase().enable_load_extension(true);
                    try (Statement vecStmt = connection.createStatement()) {
                        vecStmt.execute("SELECT load_extension('" + vecExtensionPath + "')");
                    }
                }

                // Log INFO only on first successful load — not on every connection.
                if (vectorSearchEnabled.compareAndSet(false, true)) {
                    log.infof("FtsConnectionInterceptor: sqlite-vec loaded successfully" +
                              " from path: %s", vecExtensionPath);
                }

            } catch (Exception e) {
                // Warn once — repeated failure for the same .so path is not actionable.
                if (warnedUnavailable.compareAndSet(false, true)) {
                    log.warnf("FtsConnectionInterceptor: sqlite-vec unavailable at " +
                              "path '%s' — vector search degraded to FTS5-only. " +
                              "Ensure the correct platform binary exists under ./lib/",
                              vecExtensionPath);
                }
                log.debugf(e, "Full stacktrace for vector load failure: %s",
                           e.getMessage());
            }

        } catch (Exception e) {
            if (Arc.container() != null) {
                log.warnf("FtsConnectionInterceptor: Failed to verify/attach " +
                          "fts schema: %s", e.getMessage());
                log.debugf(e, "Full stacktrace for fts attachment failure");
            }
        }
    }

    public boolean isVectorSearchEnabled() {
        return vectorSearchEnabled.get();
    }
}
