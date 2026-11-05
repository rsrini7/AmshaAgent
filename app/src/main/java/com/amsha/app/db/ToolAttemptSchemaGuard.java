package com.amsha.app.db;

import javax.sql.DataSource;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.SchemaContract;
import com.amsha.persistence.fs.FsSchemaContractManager;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;

/**
 * Eager startup guard that verifies the tool-attempt settlement schema contract.
 *
 * <p>Under ADR-Tool-Attempt-Settlement-Reset-Only-V1-Baseline, Amsha requires a reset-only
 * rollout where clean-install baselines include the required journal tables and the
 * schema contract marker. Because {@code baseline-on-migrate} is enabled in default/test/postgres
 * configurations, a non-empty database without Flyway history would baseline without executing V1.
 * This guard ensures that any un-reset or incompatible database fails fast during startup before
 * readiness or tool dispatch. It never attempts schema repair or migration.
 */
@Startup
@Readiness
@ApplicationScoped
public class ToolAttemptSchemaGuard implements HealthCheck {

    private static final Logger log = Logger.getLogger(ToolAttemptSchemaGuard.class);

    public static final String CONTRACT_NAME = SchemaContract.CONTRACT_NAME;
    public static final int CONTRACT_VERSION = SchemaContract.CONTRACT_VERSION;
    public static final String HEALTH_CHECK_NAME = "Tool Attempt Schema Contract";

    public static final List<String> REQUIRED_JOURNAL_TABLES = List.of(
            SchemaContract.SCHEMA_CONTRACT_MARKER_TABLE,
            "tool_attempt",
            "tool_action_claim",
            "tool_action_lock",
            "tool_attempt_outbox",
            "audit_chain_lock",
            "tool_attempt_resolution",
            "reasoning_step_attempt");

    @ConfigProperty(name = PersistenceBackend.PROPERTY, defaultValue = PersistenceBackend.SQLITE)
    String runtimeBackend;

    @ConfigProperty(name = "amsha.fs.data-dir", defaultValue = "./data/fs")
    String fsDataDir;

    @Inject
    Instance<DataSource> dataSourceInstance;

    @Inject
    Instance<FsSchemaContractManager> fsSchemaContractManagerInstance;

    private volatile boolean verified = false;

    /**
     * Executes after Flyway migration and WAL setup (@Priority 10), but before application-level
     * services, readiness probes, and tool dispatch.
     */
    @Priority(20)
    void onStart(@Observes @Priority(20) StartupEvent ev) {
        if (PersistenceBackend.isFilesystem(runtimeBackend)) {
            verifyFilesystemBackend();
        } else {
            verifyDatabaseBackend();
        }
        this.verified = true;
        log.infof(
                "ToolAttemptSchemaGuard: verified schema contract '%s' (v%d) on backend '%s'.",
                CONTRACT_NAME, CONTRACT_VERSION, runtimeBackend);
    }

    private void verifyFilesystemBackend() {
        if (fsSchemaContractManagerInstance != null && fsSchemaContractManagerInstance.isResolvable()) {
            fsSchemaContractManagerInstance.get().verifyOrInitialize();
        } else {
            new FsSchemaContractManager(Path.of(fsDataDir).toAbsolutePath().normalize()).verifyOrInitialize();
        }
    }

    private void verifyDatabaseBackend() {
        if (dataSourceInstance == null || !dataSourceInstance.isResolvable()) {
            throw new IllegalStateException("Tool attempt settlement schema contract not satisfied: "
                    + "DataSource is not available for backend '" + runtimeBackend + "'.");
        }

        DataSource ds = dataSourceInstance.get();
        try (Connection conn = ds.getConnection()) {
            validateDatabaseSchema(conn);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Tool attempt settlement schema contract not satisfied: " + "failed to verify database schema: "
                            + e.getMessage(),
                    e);
        }
    }

    public void validateDatabaseSchema(Connection conn) throws SQLException {
        Set<String> existingTables = getTableNames(conn);

        for (String table : REQUIRED_JOURNAL_TABLES) {
            if (!existingTables.contains(table.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Tool attempt settlement schema contract not satisfied: "
                        + "missing required table '" + table + "'. "
                        + "Database has not been reset to the clean V1 baseline.");
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT contract_name, contract_version FROM schema_contract_marker WHERE contract_name = ?")) {
            ps.setString(1, CONTRACT_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Tool attempt settlement schema contract not satisfied: "
                            + "marker row for contract '" + CONTRACT_NAME + "' not found in schema_contract_marker.");
                }
                int version = rs.getInt("contract_version");
                if (version != CONTRACT_VERSION) {
                    throw new IllegalStateException("Tool attempt settlement schema contract not satisfied: "
                            + "contract '" + CONTRACT_NAME + "' version mismatch. Expected version "
                            + CONTRACT_VERSION + " but found version " + version + ".");
                }
            }
        }
    }

    public static Set<String> getTableNames(Connection conn) throws SQLException {
        Set<String> tableNames = new HashSet<>();
        DatabaseMetaData meta = conn.getMetaData();
        String schema = resolveActiveSchema(conn);
        try (ResultSet rs = meta.getTables(null, schema, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null) {
                    tableNames.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (tableNames.isEmpty()) {
            try (ResultSet rs = meta.getTables(null, schema, "%", null)) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name != null) {
                        tableNames.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return tableNames;
    }

    private static String resolveActiveSchema(Connection conn) {
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema;
            }
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException _) {
            // Some drivers do not support getSchema()
        }
        return null;
    }

    public boolean isVerified() {
        return verified;
    }

    @Override
    public HealthCheckResponse call() {
        if (verified) {
            return HealthCheckResponse.named(HEALTH_CHECK_NAME)
                    .up()
                    .withData("contract", CONTRACT_NAME)
                    .withData("version", CONTRACT_VERSION)
                    .withData("backend", runtimeBackend)
                    .build();
        }
        return HealthCheckResponse.named(HEALTH_CHECK_NAME)
                .down()
                .withData("error", "Schema contract verification has not completed")
                .withData("backend", runtimeBackend)
                .build();
    }
}
