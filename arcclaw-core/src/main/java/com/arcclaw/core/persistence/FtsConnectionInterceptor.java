package com.arcclaw.core.persistence;

import io.agroal.api.AgroalPoolInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Hardens SQLite FTS5 connection pooling by verifying/attaching the fts schema
 * on every connection acquisition. This prevents "no such table" or "database fts is already in use"
 * errors across different pool threads.
 */
@ApplicationScoped
public class FtsConnectionInterceptor implements AgroalPoolInterceptor {

    private static final Logger log = Logger.getLogger(FtsConnectionInterceptor.class);

    @ConfigProperty(name = "ARCCLAW_DATA_DIR", defaultValue = "./data")
    String dataDir;

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
        } catch (Exception e) {
            log.warnf("FtsConnectionInterceptor: Failed to verify/attach fts schema: %s", e.getMessage());
        }
    }
}
