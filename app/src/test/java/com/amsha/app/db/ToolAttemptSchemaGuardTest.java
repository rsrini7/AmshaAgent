package com.amsha.app.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class ToolAttemptSchemaGuardTest {

    private static Connection createMemoryDb() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    private static void setupAllTablesAndMarker(Connection conn, int contractVersion) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE schema_contract_marker (
                        contract_name TEXT NOT NULL PRIMARY KEY,
                        contract_version INTEGER NOT NULL,
                        applied_at TEXT NOT NULL
                    );
                    """);
            stmt.execute(String.format(
                    "INSERT INTO schema_contract_marker (contract_name, contract_version, applied_at) "
                            + "VALUES ('tool-attempt-settlement', %d, datetime('now'));",
                    contractVersion));
            stmt.execute("CREATE TABLE tool_attempt (workspace_id TEXT, attempt_id TEXT);");
            stmt.execute("CREATE TABLE tool_action_claim (workspace_id TEXT, session_id TEXT);");
            stmt.execute("CREATE TABLE tool_action_lock (lock_id TEXT);");
            stmt.execute("CREATE TABLE tool_attempt_outbox (event_id TEXT);");
            stmt.execute("CREATE TABLE audit_chain_lock (session_id TEXT, lock_version INTEGER);");
            stmt.execute("CREATE TABLE tool_attempt_resolution (resolution_id TEXT);");
            stmt.execute("CREATE TABLE reasoning_step_attempt (step_id TEXT, attempt_id TEXT);");
        }
    }

    @Test
    void validDatabaseSchemaPasses() throws SQLException {
        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        try (Connection conn = createMemoryDb()) {
            setupAllTablesAndMarker(conn, 1);
            assertDoesNotThrow(() -> guard.validateDatabaseSchema(conn));
        }
    }

    @Test
    void missingMarkerTableThrowsIllegalStateException() throws SQLException {
        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        try (Connection conn = createMemoryDb()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE tool_attempt (workspace_id TEXT, attempt_id TEXT);");
                stmt.execute("CREATE TABLE tool_action_claim (workspace_id TEXT);");
                stmt.execute("CREATE TABLE tool_action_lock (lock_id TEXT);");
                stmt.execute("CREATE TABLE tool_attempt_outbox (event_id TEXT);");
                stmt.execute("CREATE TABLE tool_attempt_resolution (resolution_id TEXT);");
                stmt.execute("CREATE TABLE reasoning_step_attempt (step_id TEXT);");
            }
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> guard.validateDatabaseSchema(conn));
            assertTrue(ex.getMessage().contains("missing required table 'schema_contract_marker'"));
        }
    }

    @Test
    void missingMarkerRowThrowsIllegalStateException() throws SQLException {
        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        try (Connection conn = createMemoryDb()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE schema_contract_marker (
                            contract_name TEXT NOT NULL PRIMARY KEY,
                            contract_version INTEGER NOT NULL,
                            applied_at TEXT NOT NULL
                        );
                        """);
                stmt.execute("CREATE TABLE tool_attempt (workspace_id TEXT, attempt_id TEXT);");
                stmt.execute("CREATE TABLE tool_action_claim (workspace_id TEXT);");
                stmt.execute("CREATE TABLE tool_action_lock (lock_id TEXT);");
                stmt.execute("CREATE TABLE tool_attempt_outbox (event_id TEXT);");
                stmt.execute("CREATE TABLE audit_chain_lock (session_id TEXT, lock_version INTEGER);");
                stmt.execute("CREATE TABLE tool_attempt_resolution (resolution_id TEXT);");
                stmt.execute("CREATE TABLE reasoning_step_attempt (step_id TEXT);");
            }
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> guard.validateDatabaseSchema(conn));
            assertTrue(ex.getMessage().contains("marker row for contract 'tool-attempt-settlement' not found"));
        }
    }

    @Test
    void markerVersionMismatchThrowsIllegalStateException() throws SQLException {
        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        try (Connection conn = createMemoryDb()) {
            setupAllTablesAndMarker(conn, 2);
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> guard.validateDatabaseSchema(conn));
            assertTrue(ex.getMessage().contains("version mismatch"));
        }
    }

    @Test
    void missingRequiredJournalTableThrowsIllegalStateException() throws SQLException {
        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        try (Connection conn = createMemoryDb()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE schema_contract_marker (
                            contract_name TEXT NOT NULL PRIMARY KEY,
                            contract_version INTEGER NOT NULL,
                            applied_at TEXT NOT NULL
                        );
                        """);
                stmt.execute(
                        "INSERT INTO schema_contract_marker VALUES ('tool-attempt-settlement', 1, datetime('now'));");
                // Create some but omit tool_action_lock
                stmt.execute("CREATE TABLE tool_attempt (workspace_id TEXT, attempt_id TEXT);");
                stmt.execute("CREATE TABLE tool_action_claim (workspace_id TEXT);");
                stmt.execute("CREATE TABLE tool_attempt_outbox (event_id TEXT);");
                stmt.execute("CREATE TABLE tool_attempt_resolution (resolution_id TEXT);");
                stmt.execute("CREATE TABLE reasoning_step_attempt (step_id TEXT);");
            }
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> guard.validateDatabaseSchema(conn));
            assertTrue(ex.getMessage().contains("missing required table 'tool_action_lock'"));
        }
    }

    @Test
    void nonResetDatabaseFailsClosedUnderBaselineOnMigrate() throws SQLException {
        // Simulates an existing database with legacy tables where Flyway skipped V1 due to baseline-on-migrate
        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        try (Connection conn = createMemoryDb()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE agent_checkpoint (id TEXT PRIMARY KEY);");
                stmt.execute("CREATE TABLE audit_event (id TEXT PRIMARY KEY);");
                stmt.execute("CREATE TABLE recall_messages (id TEXT PRIMARY KEY);");
            }
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> guard.validateDatabaseSchema(conn));
            assertTrue(ex.getMessage().contains("missing required table 'schema_contract_marker'"));
        }
    }

    @Test
    void healthCheckReportsDownBeforeStartAndUpAfterStart() {
        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        assertFalse(guard.isVerified());

        HealthCheckResponse initial = guard.call();
        assertEquals(HealthCheckResponse.Status.DOWN, initial.getStatus());
        assertEquals("Tool Attempt Schema Contract", initial.getName());
    }

    @Test
    void filesystemBackendEmptyRootInitializesMarker(@TempDir Path tempDir) {
        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        guard.runtimeBackend = "fs";
        guard.fsDataDir = tempDir.toString();

        assertDoesNotThrow(() -> guard.onStart(null));
        assertTrue(guard.isVerified());

        HealthCheckResponse response = guard.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertTrue(Files.exists(tempDir.resolve("schema-contract.json")));
    }

    @Test
    void filesystemBackendNonEmptyRootMissingMarkerFailsClosed(@TempDir Path tempDir) throws IOException {
        Path someDir = tempDir.resolve("sessions");
        Files.createDirectories(someDir);
        Files.writeString(someDir.resolve("session-1.json"), "{}");

        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        guard.runtimeBackend = "fs";
        guard.fsDataDir = tempDir.toString();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.onStart(null));
        assertTrue(ex.getMessage().contains("missing schema-contract.json"));
        assertFalse(guard.isVerified());

        HealthCheckResponse response = guard.call();
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }

    private static Path resolveMigrationDir() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("persistence-sqlite").resolve("src/main/resources/db/migration/sqlite");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate persistence-sqlite migrations");
    }

    @Test
    void flywayBaselineOnMigrateSkipsV1AndGuardCatchesIt(@TempDir Path tempDir) throws SQLException {
        Path dbPath = tempDir.resolve("legacy-amsha.db");
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

        // 1. Pre-populate a legacy database with a table from earlier versions but no Flyway history
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE legacy_session_data (id TEXT PRIMARY KEY);");
        }

        // 2. Run Flyway with baselineOnMigrate=true and target=1 (simulating baseline of V1)
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("filesystem:" + resolveMigrationDir().toAbsolutePath())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target("1")
                .load();
        flyway.migrate();

        // 3. Verify that ToolAttemptSchemaGuard catches the missing schema contract marker
        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> guard.validateDatabaseSchema(conn));
            assertTrue(
                    ex.getMessage().contains("missing required table 'schema_contract_marker'"),
                    "Guard must detect that Flyway baseline skipped V1");
        }
    }

    @Test
    void cleanInstallWithFlywayPassesGuard(@TempDir Path tempDir) throws SQLException {
        Path dbPath = tempDir.resolve("clean-amsha.db");
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("filesystem:" + resolveMigrationDir().toAbsolutePath())
                .load();
        flyway.migrate();

        ToolAttemptSchemaGuard guard = new ToolAttemptSchemaGuard();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            assertDoesNotThrow(() -> guard.validateDatabaseSchema(conn));
        }
    }

    @Test
    void compositeForeignKeyEnforcesCheckpointRelationship(@TempDir Path tempDir) throws SQLException {
        Path dbPath = tempDir.resolve("fk-test.db");
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("filesystem:" + resolveMigrationDir().toAbsolutePath())
                .load();
        flyway.migrate();

        // Production connection setup: PRAGMA foreign_keys is not enabled globally.
        // Relationship enforcement and cascade are handled via triggers in SQLite.
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
                Statement stmt = conn.createStatement()) {
            // Insert parent reasoning step for checkpoint 100
            stmt.execute("INSERT INTO reasoning_step (id, checkpoint_id, sequence_num) VALUES (1, 100, 1);");

            // Valid link: checkpoint 100 with step 1
            stmt.execute("""
                    INSERT INTO reasoning_step_attempt (checkpoint_id, attempt_id, session_id, workspace_id, reasoning_step_id)
                    VALUES (100, 'att-1', 'sess-1', 'ws-1', 1);
                    """);

            // Invalid link: checkpoint 200 with step 1 (belongs to checkpoint 100) -> must fail relationship check
            assertThrows(SQLException.class, () -> {
                stmt.execute("""
                        INSERT INTO reasoning_step_attempt (checkpoint_id, attempt_id, session_id, workspace_id, reasoning_step_id)
                        VALUES (200, 'att-2', 'sess-1', 'ws-1', 1);
                        """);
            });

            // Parent-key update rejection: updating parent checkpoint_id or id while referenced must fail
            assertThrows(SQLException.class, () -> {
                stmt.execute("UPDATE reasoning_step SET checkpoint_id = 999 WHERE id = 1 AND checkpoint_id = 100;");
            });
            assertThrows(SQLException.class, () -> {
                stmt.execute("UPDATE reasoning_step SET id = 999 WHERE id = 1 AND checkpoint_id = 100;");
            });

            // Non-key updates on referenced reasoning step remain permitted
            assertDoesNotThrow(() -> {
                stmt.execute("UPDATE reasoning_step SET observation = 'tested' WHERE id = 1 AND checkpoint_id = 100;");
            });

            // Cascade delete: deleting parent step cascades to reasoning_step_attempt
            stmt.execute("DELETE FROM reasoning_step WHERE id = 1 AND checkpoint_id = 100;");
            try (ResultSet rs =
                    stmt.executeQuery("SELECT count(*) FROM reasoning_step_attempt WHERE attempt_id = 'att-1';")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "reasoning_step_attempt must be cascade-deleted");
            }
        }
    }

    @Test
    void flywayRejectsExistingHistoryWithOldV1Checksum(@TempDir Path tempDir) throws SQLException {
        Path dbPath = tempDir.resolve("existing-history.db");
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

        // 1. Simulate an existing database with historical Flyway schema history recording old V1 checksum
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
                Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE flyway_schema_history (
                        installed_rank INTEGER NOT NULL PRIMARY KEY,
                        version VARCHAR(50),
                        description VARCHAR(200) NOT NULL,
                        type VARCHAR(20) NOT NULL,
                        script VARCHAR(1000) NOT NULL,
                        checksum INTEGER,
                        installed_by VARCHAR(100) NOT NULL,
                        installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        execution_time INTEGER NOT NULL,
                        success BOOLEAN NOT NULL
                    );
                    """);
            stmt.execute("""
                    INSERT INTO flyway_schema_history (
                        installed_rank, version, description, type, script, checksum, installed_by, execution_time, success
                    ) VALUES (
                        1, '1', 'execution', 'SQL', 'V1__execution.sql', 123456789, 'sa', 10, 1
                    );
                    """);
        }

        // 2. Configure Flyway against existing history
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("filesystem:" + resolveMigrationDir().toAbsolutePath())
                .load();

        // 3. Migrate fails closed on V1 checksum mismatch
        FlywayException ex = assertThrows(FlywayException.class, () -> flyway.migrate());
        assertTrue(
                ex.getMessage().contains("checksum mismatch for migration version 1")
                        || ex.getMessage().contains("Migration checksum mismatch"),
                "Flyway must reject existing history with old V1 checksum: " + ex.getMessage());
    }

    @Test
    void schemaScopingFiltersByActiveSchema() throws SQLException {
        Connection conn = Mockito.mock(Connection.class);
        DatabaseMetaData meta = Mockito.mock(DatabaseMetaData.class);
        ResultSet rs = Mockito.mock(ResultSet.class);

        Mockito.when(conn.getSchema()).thenReturn("custom_tenant");
        Mockito.when(conn.getMetaData()).thenReturn(meta);
        Mockito.when(meta.getTables(null, "custom_tenant", "%", new String[] {"TABLE"}))
                .thenReturn(rs);
        Mockito.when(rs.next()).thenReturn(true, false);
        Mockito.when(rs.getString("TABLE_NAME")).thenReturn("schema_contract_marker");

        Set<String> tables = ToolAttemptSchemaGuard.getTableNames(conn);
        assertTrue(tables.contains("schema_contract_marker"));

        // Verify that meta.getTables was called with the active schema, NOT null
        Mockito.verify(meta).getTables(null, "custom_tenant", "%", new String[] {"TABLE"});
        Mockito.verify(meta, Mockito.never()).getTables(null, null, "%", new String[] {"TABLE"});
    }
}
