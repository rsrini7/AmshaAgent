package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;

/**
 * Plain JUnit coverage for {@link FtsSchemaInitializer}.
 *
 * <p>In the {@code @QuarkusTest} application the FTS schema has already been attached by
 * {@link FtsConnectionInterceptor} by the time this initializer observes {@code StartupEvent}, so the
 * ATTACH arm and all three failure arms never execute there. They are driven here against a mocked
 * {@link AgroalDataSource}, which is an interface and therefore directly mockable.
 */
class FtsSchemaInitializerTest {

    private static final String ATTACH_SQL = "ATTACH DATABASE 'data/amsha-fts.db' AS fts";
    private static final String COUNT_SQL = "SELECT count(*) FROM fts.compaction_summary_fts";

    private AgroalDataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ResultSet databaseList;
    private ResultSet countResult;
    private FtsSchemaInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(AgroalDataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        databaseList = mock(ResultSet.class);
        countResult = mock(ResultSet.class);
        initializer = new FtsSchemaInitializer(dataSource);
        initializer.jdbcUrl = "jdbc:sqlite:./data/amsha-meta.db";

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("PRAGMA database_list")).thenReturn(databaseList);
        when(statement.executeQuery(startsWith("SELECT count(*) FROM"))).thenReturn(countResult);
        when(countResult.next()).thenReturn(true);
        when(countResult.getLong(1)).thenReturn(3L);
    }

    @Test
    @DisplayName("non-SQLite datasource -> skipped without touching the pool")
    void nonSqliteDatasourceIsSkipped() {
        initializer.jdbcUrl = "jdbc:postgresql://localhost:5432/amsha";

        initializer.onStartup(new StartupEvent());

        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("schema absent -> ATTACH fires, the FTS5 table is created, and the count is verified")
    void attachesSchemaWhenAbsent() throws Exception {
        when(databaseList.next()).thenReturn(false);

        initializer.onStartup(new StartupEvent());

        verify(statement).execute(ATTACH_SQL);
        verify(statement)
                .execute(startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS fts.compaction_summary_fts USING fts5("));
        verify(statement).executeQuery(COUNT_SQL);
    }

    @Test
    @DisplayName("schema already attached by the pool interceptor -> ATTACH is skipped")
    void alreadyAttachedSchemaSkipsTheAttach() throws Exception {
        when(databaseList.next()).thenReturn(true, false);
        when(databaseList.getString("name")).thenReturn("fts");

        initializer.onStartup(new StartupEvent());

        verify(statement, never()).execute(ATTACH_SQL);
        verify(statement)
                .execute(startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS fts.compaction_summary_fts USING fts5("));
    }

    @Test
    @DisplayName("ATTACH failure is warned and the remaining schema steps still run")
    void attachFailureIsWarnedAndDoesNotAbort() throws Exception {
        when(databaseList.next()).thenReturn(false);
        when(statement.execute(ATTACH_SQL)).thenThrow(new SQLException("unable to open database file"));

        assertDoesNotThrow(() -> initializer.onStartup(new StartupEvent()));

        verify(statement)
                .execute(startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS fts.compaction_summary_fts USING fts5("));
        verify(statement).executeQuery(COUNT_SQL);
    }

    @Test
    @DisplayName("virtual-table creation failure is warned and verification still runs")
    void tableCreationFailureIsWarned() throws Exception {
        when(databaseList.next()).thenReturn(false);
        when(statement.execute(startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS")))
                .thenThrow(new SQLException("no such module: fts5"));

        assertDoesNotThrow(() -> initializer.onStartup(new StartupEvent()));

        verify(statement).executeQuery(COUNT_SQL);
    }

    @Test
    @DisplayName("verification failure is warned and never propagates")
    void verificationFailureIsWarned() throws Exception {
        when(databaseList.next()).thenReturn(false);
        when(statement.executeQuery(COUNT_SQL)).thenThrow(new SQLException("database is locked"));

        assertDoesNotThrow(() -> initializer.onStartup(new StartupEvent()));

        verify(statement).execute(ATTACH_SQL);
    }
}
