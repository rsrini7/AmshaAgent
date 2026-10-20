package com.amsha.persistence.sqlite;

import jakarta.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.PersistenceBackend;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.sqlite.SQLiteConnection;

import io.agroal.api.AgroalPoolInterceptor;
import io.quarkus.arc.Arc;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Hardens SQLite FTS5 connection pooling by verifying/attaching the fts schema
 * on every connection acquisition. This prevents "no such table" or "database
 * fts is already in use" errors across different pool threads.
 *
 * <p><strong>sqlite-vec loading contract:</strong>
 * <ul>
 *   <li>SQLite extensions are per-connection (per sqlite3* handle).</li>
 *   <li>Agroal REUSES physical connections - onConnectionAcquire fires on
 *       every logical borrow, including re-borrows of the same sqlite3* handle.</li>
 *   <li>Calling load_extension twice on the same sqlite3* handle fails with
 *       "error during initialization:" because the module is already registered.</li>
 *   <li>Fix: check pragma_module_list on each connection before loading -
 *       skip if vec0 is already registered on that specific handle.</li>
 *   <li>When not using SQLite backend, this bean is deactivated and Agroal ignores pool interceptor mappings if not loaded.</li>
 * </ul>
 *
 * Encapsulated inside amsha-persistence-sqlite adapter module.
 * Registered with Agroal via Quarkus configuration.
 */
@Singleton
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.SQLITE, enableIfMissing = true)
public class FtsConnectionInterceptor implements AgroalPoolInterceptor {

    private static final Logger log = Logger.getLogger(FtsConnectionInterceptor.class);

    @ConfigProperty(name = "quarkus.datasource.jdbc.url", defaultValue = "jdbc:sqlite:./data/amsha-meta.db")
    String jdbcUrl;

    @ConfigProperty(name = "amsha.rag.vec-extension-path", defaultValue = "./lib/vec0")
    String vecExtensionPath;

    /** True once any connection has successfully loaded the sqlite-vec module. */
    private final AtomicBoolean vectorSearchEnabled = new AtomicBoolean(false);

    /** Guards the "vec0 unavailable" WARN so it fires at most once. */
    private final AtomicBoolean warnedUnavailable = new AtomicBoolean(false);

    /** Guards the "fts attachment failed" WARN so it fires at most once. */
    private final AtomicBoolean warnedFtsAttachment = new AtomicBoolean(false);

    @Override
    public void onConnectionAcquire(Connection connection) {
        if (Arc.container() == null) {
            return;
        }
        try {
            attachFtsSchemaIfNeeded(connection);
            loadVectorExtensionIfNeeded(connection);
        } catch (Exception e) {
            if (Arc.container() != null) {
                if (warnedFtsAttachment.compareAndSet(false, true)) {
                    log.warnf(
                            "FtsConnectionInterceptor: Failed to verify/attach "
                                    + "fts schema (will suppress subsequent warnings): %s",
                            LogUtil.rootMessage(e));
                }
                log.debugf("Full stacktrace for fts attachment failure: %s", LogUtil.trimmedStack(e));
            }
        }
    }

    private void attachFtsSchemaIfNeeded(Connection connection) throws Exception {
        boolean attached = false;
        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("PRAGMA database_list")) {
                while (rs.next()) {
                    if ("fts".equals(rs.getString("name"))) {
                        attached = true;
                        break;
                    }
                }
            }
            try {
                stmt.execute("PRAGMA busy_timeout=10000");
            } catch (Exception e) {
                log.debugf("FtsConnectionInterceptor: Could not set busy_timeout=10000: %s", LogUtil.rootMessage(e));
            }
            try {
                stmt.execute("PRAGMA synchronous=NORMAL");
            } catch (Exception e) {
                // Expected when connection is already inside a transaction — SQLite rejects
                // safety-level changes mid-transaction. Harmless; suppress to TRACE.
                log.tracef(
                        "FtsConnectionInterceptor: Could not set synchronous=NORMAL (in transaction?): %s",
                        LogUtil.rootMessage(e));
            }
        }
        if (!attached) {
            if (!connection.getAutoCommit()) {
                log.debug(
                        "FtsConnectionInterceptor: Cannot ATTACH fts (transaction active). Will retry on next acquire.");
            } else {
                File ftsDb = ftsDbFile();
                try {
                    Files.createDirectories(ftsDb.getParentFile().toPath());
                } catch (IOException e) {
                    log.warnf(
                            LogUtil.relevantThrowable(e),
                            "Failed to create data directory for FTS: %s",
                            ftsDb.getParent());
                }
                String ftsPath = ftsDb.getAbsolutePath().replace("\\", "/");
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("ATTACH DATABASE '" + ftsPath + "' AS fts");
                }
            }
        }
    }

    private void loadVectorExtensionIfNeeded(Connection connection) {
        if (warnedUnavailable.get()) {
            return;
        }
        try {
            boolean alreadyLoaded = false;
            try (Statement checkStmt = connection.createStatement();
                    ResultSet moduleRs =
                            checkStmt.executeQuery("SELECT name FROM pragma_module_list WHERE name='vec0'")) {
                if (moduleRs.next()) {
                    alreadyLoaded = true;
                }
            }
            if (!alreadyLoaded) {
                SQLiteConnection sqliteConn = connection.unwrap(SQLiteConnection.class);
                sqliteConn.getDatabase().enable_load_extension(true);
                String absoluteVecPath = resolveExtensionPath(vecExtensionPath)
                        .toAbsolutePath()
                        .toString()
                        .replace("\\", "/");
                try (Statement vecStmt = connection.createStatement()) {
                    vecStmt.execute("SELECT load_extension('" + absoluteVecPath + "')");
                }
            }
            if (vectorSearchEnabled.compareAndSet(false, true)) {
                log.infof(
                        "FtsConnectionInterceptor: sqlite-vec loaded successfully" + " from path: %s",
                        vecExtensionPath);
            }
        } catch (Exception e) {
            if (warnedUnavailable.compareAndSet(false, true)) {
                log.warnf(
                        "FtsConnectionInterceptor: sqlite-vec unavailable at "
                                + "path '%s' - vector search degraded to FTS5-only. "
                                + "Root cause: %s",
                        vecExtensionPath, LogUtil.rootMessage(e));
                log.debugf("Full stacktrace for vector load failure: %s", LogUtil.trimmedStack(e));
            }
        }
    }

    private Path resolveExtensionPath(String rawPath) {
        Path p = Path.of(rawPath);
        if (p.isAbsolute()) {
            return p;
        }
        if (fileExistsWithOsExtension(p)) {
            return p;
        }

        // Traverse upwards from current working directory (up to 4 levels) to search for the extension.
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (int i = 0; i < 4; i++) {
            Path candidate = current.resolve(rawPath);
            if (fileExistsWithOsExtension(candidate)) {
                return candidate.normalize();
            }
            Path parent = current.getParent();
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return p;
    }

    private boolean fileExistsWithOsExtension(Path path) {
        if (Files.exists(path)) {
            return true;
        }
        String filename = path.getFileName().toString();
        Path parent = path.getParent();
        if (parent == null || !Files.exists(parent)) {
            return false;
        }
        return Files.exists(parent.resolve(filename + ".dylib"))
                || Files.exists(parent.resolve(filename + ".so"))
                || Files.exists(parent.resolve(filename + ".dll"));
    }

    public boolean isVectorSearchEnabled() {
        return vectorSearchEnabled.get();
    }

    private File ftsDbFile() {
        String path = jdbcUrl.replace("jdbc:sqlite:", "");
        int qIndex = path.indexOf('?');
        if (qIndex > 0) path = path.substring(0, qIndex);
        int sep = path.lastIndexOf('/');
        String dir = sep > 0 ? path.substring(0, sep) : ".";
        return Path.of(dir, "amsha-fts.db").toFile();
    }
}
