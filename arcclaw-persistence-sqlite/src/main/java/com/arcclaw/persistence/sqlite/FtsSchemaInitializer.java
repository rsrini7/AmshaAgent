package com.arcclaw.persistence.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.arcclaw.core.util.LogUtil;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Creates the FTS5 virtual table for compaction summary search on startup.
 * WORM audit triggers are managed by Flyway (V1__baseline.sql).
 */
@ApplicationScoped
public class FtsSchemaInitializer {

    private static final Logger LOG = Logger.getLogger(FtsSchemaInitializer.class);

    private static final String SCHEMA_NAME = "fts";
    private static final String FTS5_TABLE = "compaction_summary_fts";

    @ConfigProperty(name = "quarkus.datasource.jdbc.url", defaultValue = "")
    String jdbcUrl;

    AgroalDataSource dataSource;

    @Inject
    public FtsSchemaInitializer(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void onStartup(@Observes StartupEvent event) {
        if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            ensureFtsSchema();
            createCompactionSummaryFts5Table();
            verifyFts5Schema();
        } else {
            LOG.info("Non-SQLite datasource detected (url=%s), skipping FTS5 schema setup.".formatted(jdbcUrl));
        }
    }

    private void ensureFtsSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Check if already attached (FtsConnectionInterceptor may have attached it)
            try (ResultSet rs = stmt.executeQuery("PRAGMA database_list")) {
                while (rs.next()) {
                    if (SCHEMA_NAME.equals(rs.getString("name"))) {
                        LOG.debug("FTS schema already attached by connection interceptor, skipping.");
                        return;
                    }
                }
            }
            stmt.execute("ATTACH DATABASE 'data/arcclaw-fts.db' AS " + SCHEMA_NAME);
            LOG.info("FTS schema attached successfully.");
        } catch (Exception e) {
            LOG.warnf("Could not attach FTS schema: %s", LogUtil.rootMessage(e));
        }
    }

    private void createCompactionSummaryFts5Table() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS "
                    + SCHEMA_NAME + "." + FTS5_TABLE
                    + " USING fts5(sessionId UNINDEXED, summary)"
            );
            LOG.infof("FTS5 virtual table '%s.%s' created or verified.", SCHEMA_NAME, FTS5_TABLE);
        } catch (Exception e) {
            LOG.warnf("Could not create FTS5 virtual table '%s.%s': %s", SCHEMA_NAME, FTS5_TABLE, LogUtil.rootMessage(e));
        }
    }

    private void verifyFts5Schema() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT count(*) FROM " + SCHEMA_NAME + "." + FTS5_TABLE)) {
                if (rs.next()) {
                    long count = rs.getLong(1);
                    LOG.infof("Audit schema enforcement complete. Audit event count: %d", count);
                }
            }
        } catch (Exception e) {
            LOG.warnf("Could not verify audit event count: %s", LogUtil.rootMessage(e));
        }
    }
}
