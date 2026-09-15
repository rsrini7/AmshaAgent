package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.runtime.StartupEvent;

/**
 * Plain JUnit coverage for {@link SqliteWalConfigurer}.
 *
 * <p>In the {@code @QuarkusTest} application the FTS database is already attached by
 * {@link FtsConnectionInterceptor} on connection acquire, so the boot path always takes the
 * "already in use" tolerance arm. The success arm, the directory-creation arm and the unexpected-failure
 * arm are driven here against a mocked {@link DataSource}.
 */
class SqliteWalConfigurerTest {

    @TempDir
    Path tempDir;

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private SqliteWalConfigurer configurer;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        configurer = new SqliteWalConfigurer();
        configurer.dataSource = dataSource;

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
    }

    @Test
    @DisplayName("missing FTS directory is created and the database is attached")
    void createsFtsDirectoryAndAttaches() throws Exception {
        Path ftsDir = tempDir.resolve("nested");
        configurer.jdbcUrl = "jdbc:sqlite:" + ftsDir.resolve("meta.db");

        configurer.onStartup(new StartupEvent());

        assertTrue(Files.isDirectory(ftsDir), "the FTS database directory must be created before ATTACH");
        verify(statement).execute("PRAGMA journal_mode=WAL;");
        verify(statement).execute("PRAGMA synchronous=NORMAL;");
        verify(statement).execute("PRAGMA busy_timeout=10000;");
        verify(statement).execute(contains("ATTACH DATABASE"));
    }

    @Test
    @DisplayName("an existing FTS directory is reused without re-creating it")
    void existingFtsDirectoryIsReused() throws Exception {
        configurer.jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("meta.db");

        configurer.onStartup(new StartupEvent());

        assertTrue(Files.isDirectory(tempDir));
        verify(statement).execute(contains("ATTACH DATABASE"));
    }

    @Test
    @DisplayName("an 'already in use' attach failure is tolerated silently")
    void alreadyInUseAttachFailureIsTolerated() throws Exception {
        configurer.jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("meta.db");
        when(statement.execute(contains("ATTACH DATABASE")))
                .thenThrow(new SQLException("database fts is already in use"));

        assertDoesNotThrow(() -> configurer.onStartup(new StartupEvent()));
    }

    @Test
    @DisplayName("any other attach failure is rethrown into the outer catch and logged, never propagated")
    void unexpectedAttachFailureIsSwallowedByTheOuterCatch() throws Exception {
        configurer.jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("meta.db");
        when(statement.execute(contains("ATTACH DATABASE"))).thenThrow(new SQLException("disk I/O error"));

        assertDoesNotThrow(
                () -> configurer.onStartup(new StartupEvent()),
                "boot must degrade rather than fail when the FTS attach fails for an unexpected reason");
    }
}
