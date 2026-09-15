package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Plain JUnit coverage for {@link FtsCleanCallback} and its private {@code BeforeCleanCallback}.
 *
 * <p>{@code FtsCleanCallback} is a {@code FlywayConfigurationCustomizer}: the callback body only ever runs
 * during a Flyway {@code clean}, which no test in this module performs, so the drop statements and the
 * {@code SQLException} tolerance arm are unreachable in an integration run. This class captures the
 * registered callback and invokes it directly.
 */
class FtsCleanCallbackTest {

    /** Registers the callback through the public customizer and returns what Flyway would invoke. */
    private static Callback beforeCleanCallback() {
        FluentConfiguration configuration = mock(FluentConfiguration.class);

        new FtsCleanCallback().customize(configuration);

        ArgumentCaptor<Callback> captor = ArgumentCaptor.forClass(Callback.class);
        verify(configuration).callbacks(captor.capture());
        return captor.getValue();
    }

    private static Context contextWith(Connection connection) {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }

    @Test
    @DisplayName("the callback declares support for BEFORE_CLEAN only")
    void supportsBeforeCleanOnly() {
        Callback callback = beforeCleanCallback();

        assertTrue(callback.supports(Event.BEFORE_CLEAN, null));
        assertFalse(callback.supports(Event.AFTER_CLEAN, null));
    }

    @Test
    @DisplayName("dropping the FTS5 and vec0 virtual tables runs before clean")
    void dropsVirtualTablesBeforeClean() throws Exception {
        Callback callback = beforeCleanCallback();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);

        callback.handle(Event.BEFORE_CLEAN, contextWith(connection));

        // Dropping the parent virtual table cascades removal of the shadow tables that Flyway's SQLite
        // clean cannot drop directly, so all three drops must be issued.
        verify(statement).execute("DROP TABLE IF EXISTS recall_messages_fts");
        verify(statement).execute("DROP TABLE IF EXISTS archival_chunks_fts");
        verify(statement).execute("DROP TABLE IF EXISTS vec_archival");
    }

    @Test
    @DisplayName("a failing drop is swallowed so clean is not blocked")
    void sqlFailureIsSwallowed() throws Exception {
        Callback callback = beforeCleanCallback();
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenThrow(new SQLException("database is closed"));

        assertDoesNotThrow(() -> callback.handle(Event.BEFORE_CLEAN, contextWith(connection)));
    }
}
