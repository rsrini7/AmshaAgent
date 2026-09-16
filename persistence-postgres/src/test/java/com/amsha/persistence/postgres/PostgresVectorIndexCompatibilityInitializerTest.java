package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.EmbeddingIndexDescriptorSource;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.result.ResultIterable;
import org.jdbi.v3.core.statement.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.quarkus.runtime.StartupEvent;

/**
 * Plain JUnit coverage for {@link PostgresVectorIndexCompatibilityInitializer}.
 *
 * <p>Why this class exists: the {@code @QuarkusTest} application binds no
 * {@link EmbeddingIndexDescriptorSource} bean, so the real {@code StartupEvent} observer always returns at
 * the "descriptor source unavailable" guard. Everything past that guard — descriptor resolution, the
 * dimension-mismatch branch, the row-count inspection and the inspection-failure fallback — is unreachable
 * in an integration run and is driven here with mocks.
 *
 * <p>Unlike its SQLite twin this initializer validates a single namespace, so there is no per-namespace
 * isolation to prove. What is asserted instead is that the count query targets {@code rag_chunk_embedding}
 * literally (not a namespace-derived name) and that {@code count == 0} is reported as an empty index.
 */
@SuppressWarnings("UnnecessaryAssignment")
class PostgresVectorIndexCompatibilityInitializerTest {

    private static final int CONFIGURED_DIMENSION = 768;
    private static final String COUNT_SQL = "SELECT count(*) FROM rag_chunk_embedding";

    private PostgresVectorIndexCompatibilityInitializer initializer;
    private VectorIndexMetadataPort metadata;
    private Jdbi jdbi;
    private Instance<EmbeddingIndexDescriptorSource> descriptorSources;
    private EmbeddingIndexDescriptorSource source;
    private List<String> executedSql;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        initializer = new PostgresVectorIndexCompatibilityInitializer();
        metadata = mock(VectorIndexMetadataPort.class);
        jdbi = mock(Jdbi.class);
        descriptorSources = mock(Instance.class);
        source = mock(EmbeddingIndexDescriptorSource.class);
        executedSql = new ArrayList<>();

        initializer.metadata = metadata;
        initializer.jdbi = jdbi;
        initializer.descriptorSources = descriptorSources;
        initializer.configuredDimension = CONFIGURED_DIMENSION;
    }

    /** Makes the descriptor source resolvable, unambiguous, and pinned to {@code descriptor}. */
    private void givenActiveDescriptor(EmbeddingIndexDescriptor descriptor) {
        when(descriptorSources.isUnsatisfied()).thenReturn(false);
        when(descriptorSources.isAmbiguous()).thenReturn(false);
        when(descriptorSources.get()).thenReturn(source);
        when(source.activeDescriptor()).thenReturn(Optional.of(descriptor));
    }

    /** Stubs the count query to report {@code count} rows, recording the SQL it received. */
    private void givenTableRowCount(long count) {
        when(jdbi.withHandle(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            HandleCallback<Object, RuntimeException> callback = invocation.getArgument(0);
            Handle handle = mock(Handle.class);
            Query query = mock(Query.class);
            ResultIterable<Long> rows = mock(ResultIterable.class);
            when(handle.createQuery(anyString())).thenAnswer(queryInvocation -> {
                executedSql.add(queryInvocation.getArgument(0));
                return query;
            });
            when(query.mapTo(Long.class)).thenReturn(rows);
            when(rows.one()).thenReturn(count);
            return callback.withHandle(handle);
        });
    }

    private static EmbeddingIndexDescriptor descriptor(int dimension) {
        return EmbeddingIndexDescriptor.create(
                "onnx",
                "sentence-transformers/all-mpnet-base-v2",
                "revision-1",
                "revision-1",
                "onnx-transformer-v1",
                "MASKED_MEAN",
                true,
                dimension,
                "FLOAT32",
                "FIRST_MAX_TOKENS",
                "v1",
                512,
                0,
                "DIRECT");
    }

    // =========================================================================
    // Early-exit guards
    // =========================================================================

    @Test
    @DisplayName("descriptor source unsatisfied -> warn and skip, never touch the database")
    void unsatisfiedDescriptorSourceSkips() {
        when(descriptorSources.isUnsatisfied()).thenReturn(true);

        initializer.onStart(new StartupEvent());

        verifyNoInteractions(jdbi, metadata);
        verify(descriptorSources, never()).get();
    }

    @Test
    @DisplayName("descriptor source ambiguous -> warn and skip, never touch the database")
    void ambiguousDescriptorSourceSkips() {
        when(descriptorSources.isUnsatisfied()).thenReturn(false);
        when(descriptorSources.isAmbiguous()).thenReturn(true);

        initializer.onStart(new StartupEvent());

        verifyNoInteractions(jdbi, metadata);
    }

    @Test
    @DisplayName("no active descriptor -> warn and skip, never touch the database")
    void absentActiveDescriptorSkips() {
        when(descriptorSources.isUnsatisfied()).thenReturn(false);
        when(descriptorSources.isAmbiguous()).thenReturn(false);
        when(descriptorSources.get()).thenReturn(source);
        when(source.activeDescriptor()).thenReturn(Optional.empty());

        initializer.onStart(new StartupEvent());

        verifyNoInteractions(jdbi, metadata);
    }

    // =========================================================================
    // Dimension agreement
    // =========================================================================

    @Test
    @DisplayName("dimension mismatch marks the namespace unavailable and inspects nothing")
    void dimensionMismatchMarksTheNamespaceUnavailable() {
        EmbeddingIndexDescriptor stale = descriptor(CONFIGURED_DIMENSION + 1);
        givenActiveDescriptor(stale);

        initializer.onStart(new StartupEvent());

        verify(metadata).markUnavailable(VectorIndexNamespace.RAG_GLOBAL, stale);
        verify(metadata, never()).verifyOrInitialize(any(), any(), anyBoolean());
        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("matching dimension reports an empty index when the table has no rows")
    void matchingDimensionReportsAnEmptyIndex() {
        EmbeddingIndexDescriptor active = descriptor(CONFIGURED_DIMENSION);
        givenActiveDescriptor(active);
        givenTableRowCount(0);

        initializer.onStart(new StartupEvent());

        ArgumentCaptor<Boolean> indexEmpty = ArgumentCaptor.forClass(Boolean.class);
        verify(metadata).verifyOrInitialize(eq(VectorIndexNamespace.RAG_GLOBAL), eq(active), indexEmpty.capture());
        assertTrue(indexEmpty.getValue(), "count==0 must be reported as an empty index");
        verify(metadata, never()).markUnavailable(any(), any());
    }

    @Test
    @DisplayName("a populated table is reported as NOT empty")
    void populatedTableIsReportedAsNotEmpty() {
        EmbeddingIndexDescriptor active = descriptor(CONFIGURED_DIMENSION);
        givenActiveDescriptor(active);
        givenTableRowCount(7);

        initializer.onStart(new StartupEvent());

        ArgumentCaptor<Boolean> indexEmpty = ArgumentCaptor.forClass(Boolean.class);
        verify(metadata).verifyOrInitialize(eq(VectorIndexNamespace.RAG_GLOBAL), eq(active), indexEmpty.capture());
        assertFalse(indexEmpty.getValue(), "count>0 must be reported as a populated index");
    }

    @Test
    @DisplayName("the count query targets rag_chunk_embedding literally")
    void theCountQueryTargetsTheEmbeddingTable() {
        givenActiveDescriptor(descriptor(CONFIGURED_DIMENSION));
        givenTableRowCount(0);

        initializer.onStart(new StartupEvent());

        assertEquals(List.of(COUNT_SQL), executedSql);
    }

    @Test
    @DisplayName("an inspection failure marks the namespace unavailable instead of propagating")
    void inspectionFailureMarksTheNamespaceUnavailable() {
        EmbeddingIndexDescriptor active = descriptor(CONFIGURED_DIMENSION);
        givenActiveDescriptor(active);
        when(jdbi.withHandle(any())).thenThrow(new IllegalStateException("no such table: rag_chunk_embedding"));

        initializer.onStart(new StartupEvent());

        verify(metadata).markUnavailable(VectorIndexNamespace.RAG_GLOBAL, active);
        verify(metadata, never()).verifyOrInitialize(any(), any(), anyBoolean());
    }
}
