package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
 * Plain JUnit coverage for {@link ArchivalVecSchemaInitializer}.
 *
 * <p>In the {@code @QuarkusTest} application vec0 loads successfully, so the "vec0 not available" skip
 * arm and the DDL failure arm never execute there. Both are driven here with mocks.
 *
 * <p>The success assertion deliberately pins the unqualified table name: {@code vec_archival} lives in the
 * main database, not in the {@code fts} schema, because {@code archival_chunks} is in the main database.
 */
class ArchivalVecSchemaInitializerTest {

    private ArchivalVecSchemaInitializer initializer;
    private Jdbi jdbi;
    private FtsConnectionInterceptor interceptor;
    private Update update;
    private final List<String> executedSql = new ArrayList<>();

    @BeforeEach
    void setUp() {
        initializer = new ArchivalVecSchemaInitializer();
        jdbi = mock(Jdbi.class);
        interceptor = mock(FtsConnectionInterceptor.class);
        update = mock(Update.class);

        initializer.jdbi = jdbi;
        initializer.dataSource = mock(AgroalDataSource.class);
        initializer.ftsInterceptor = interceptor;
        initializer.embedDimensions = 768;
    }

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
    @DisplayName("vec0 unavailable -> warn and skip the vec_archival DDL entirely")
    void vectorUnavailableSkipsDdl() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(false);

        initializer.onStart(new StartupEvent());

        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("vec0 available -> vec_archival is created in the MAIN database with the configured dimensions")
    void createsVecArchivalInMainDatabase() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        givenRecordingHandle();

        initializer.onStart(new StartupEvent());

        assertEquals(1, executedSql.size());
        String ddl = executedSql.get(0);
        assertTrue(ddl.startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS vec_archival"), ddl);
        assertTrue(
                !ddl.contains("fts.vec_archival"),
                "vec_archival must not be schema-qualified: archival_chunks lives in the main database");
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
                "a missing vec0 module must degrade archival search to BM25-only, not fail boot");
    }
}
