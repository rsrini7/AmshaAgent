package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;

/**
 * Plain JUnit coverage for {@link RagSchemaInitializer}.
 *
 * <p>In the {@code @QuarkusTest} application vec0 loads successfully, so only the happy path runs. The
 * connection warm-up failure arm, the "vec0 not loaded" skip arm and the DDL failure arm are driven here
 * with mocks.
 */
class RagSchemaInitializerTest {

    private RagSchemaInitializer initializer;
    private Jdbi jdbi;
    private AgroalDataSource dataSource;
    private FtsConnectionInterceptor interceptor;
    private Update update;
    private final List<String> executedSql = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        initializer = new RagSchemaInitializer();
        jdbi = mock(Jdbi.class);
        dataSource = mock(AgroalDataSource.class);
        interceptor = mock(FtsConnectionInterceptor.class);
        update = mock(Update.class);

        initializer.jdbi = jdbi;
        initializer.dataSource = dataSource;
        initializer.ftsInterceptor = interceptor;
        initializer.embedDimensions = 768;

        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
    }

    /** Routes {@code jdbi.withHandle(...)} to a mocked handle that records the DDL it is asked to run. */
    private void givenRecordingHandle() {
        when(jdbi.withHandle(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            HandleCallback<Object, RuntimeException> callback = invocation.getArgument(0);
            Handle handle = mock(Handle.class);
            when(handle.createUpdate(anyString())).thenAnswer(updateInvocation -> {
                executedSql.add(updateInvocation.getArgument(0));
                return update;
            });
            return callback.withHandle(handle);
        });
    }

    @Test
    @DisplayName("connection warm-up failure aborts before any DDL is attempted")
    void warmUpFailureAbortsBeforeAnyDdl() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

        assertDoesNotThrow(() -> initializer.onStart(new StartupEvent()));

        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("vec0 unavailable -> warn and skip the vec_global DDL entirely")
    void vectorUnavailableSkipsDdl() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(false);

        initializer.onStart(new StartupEvent());

        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("vec0 available -> fts.vec_global is created with the configured dimensions")
    void createsVecGlobalWithConfiguredDimensions() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        givenRecordingHandle();

        initializer.onStart(new StartupEvent());

        assertEquals(1, executedSql.size());
        String ddl = executedSql.get(0);
        assertTrue(ddl.startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS fts.vec_global"), ddl);
        assertTrue(ddl.contains("chunk_id TEXT PRIMARY KEY"), ddl);
        assertTrue(ddl.contains("embedding float[768]"), "dimensions must come from amsha.rag.embed-dimensions");
    }

    @Test
    @DisplayName("a DDL failure is warned and never propagates out of startup")
    void ddlFailureIsWarnedAndSwallowed() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        givenRecordingHandle();
        when(update.execute()).thenThrow(new IllegalStateException("no such module: vec0"));

        assertDoesNotThrow(
                () -> initializer.onStart(new StartupEvent()),
                "a missing vec0 module must degrade to FTS5-only search, not fail boot");
    }
}
