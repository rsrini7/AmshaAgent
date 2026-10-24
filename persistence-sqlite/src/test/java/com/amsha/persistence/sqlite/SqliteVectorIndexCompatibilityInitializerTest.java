package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * Plain JUnit coverage for {@link SqliteVectorIndexCompatibilityInitializer}.
 *
 * <p>Why this class exists: the {@code @QuarkusTest} application has no
 * {@link EmbeddingIndexDescriptorSource} bean bound, so the real {@code StartupEvent} observer always
 * returns at the "descriptor source unavailable" guard. Everything past that guard — descriptor
 * resolution, the dimension-mismatch branch, both {@code verify(...)} calls and the inspection-failure
 * fallback — is unreachable in an integration run and is driven here with mocks.
 *
 * <p>Not covered, reported instead: {@code verify(...)}'s {@code tableName == null} guard. The
 * {@code NAMESPACE_TABLES} whitelist is a {@code Map.of} over both constants of
 * {@link VectorIndexNamespace}, and {@code verify} is private and only ever called with those two
 * constants, so no namespace value can reach it. It is a defensive guard over a closed set, not a
 * behaviour gap; it is deliberately left unexercised rather than faked via reflection.
 */
class SqliteVectorIndexCompatibilityInitializerTest {

    private static final int CONFIGURED_DIMENSION = 768;

    private SqliteVectorIndexCompatibilityInitializer initializer;
    private FtsConnectionInterceptor interceptor;
    private VectorIndexMetadataPort metadata;
    private Jdbi jdbi;
    private Instance<EmbeddingIndexDescriptorSource> descriptorSources;
    private EmbeddingIndexDescriptorSource source;
    private List<String> executedSql;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        initializer = new SqliteVectorIndexCompatibilityInitializer();
        interceptor = mock(FtsConnectionInterceptor.class);
        metadata = mock(VectorIndexMetadataPort.class);
        jdbi = mock(Jdbi.class);
        descriptorSources = mock(Instance.class);
        source = mock(EmbeddingIndexDescriptorSource.class);
        executedSql = new ArrayList<>();

        initializer.ftsInterceptor = interceptor;
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

    /** Stubs the whitelist count query to report {@code count} rows, recording the SQL it received. */
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
    @DisplayName("vector search disabled -> nothing is inspected at all")
    void vectorSearchDisabledSkipsEverything() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(false);

        initializer.onStart(new StartupEvent());

        verifyNoInteractions(jdbi, metadata);
        verify(descriptorSources, never()).get();
    }

    @Test
    @DisplayName("descriptor source unsatisfied -> warn and skip, never touch the database")
    void unsatisfiedDescriptorSourceSkips() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        when(descriptorSources.isUnsatisfied()).thenReturn(true);

        initializer.onStart(new StartupEvent());

        verifyNoInteractions(jdbi);
        verify(metadata).markUnavailable(VectorIndexNamespace.RAG_GLOBAL);
        verify(metadata).markUnavailable(VectorIndexNamespace.ARCHIVAL);
    }

    @Test
    @DisplayName("descriptor source ambiguous -> warn and skip, never touch the database")
    void ambiguousDescriptorSourceSkips() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        when(descriptorSources.isUnsatisfied()).thenReturn(false);
        when(descriptorSources.isAmbiguous()).thenReturn(true);

        initializer.onStart(new StartupEvent());

        verifyNoInteractions(jdbi);
        verify(metadata).markUnavailable(VectorIndexNamespace.RAG_GLOBAL);
        verify(metadata).markUnavailable(VectorIndexNamespace.ARCHIVAL);
    }

    @Test
    @DisplayName("embedding provider failure disables vector search without database access")
    void embeddingProviderFailureDisablesVectorSearch() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        when(descriptorSources.isUnsatisfied()).thenReturn(false);
        when(descriptorSources.isAmbiguous()).thenReturn(false);
        when(descriptorSources.get()).thenReturn(source);
        when(source.activeDescriptor()).thenThrow(new IllegalStateException("embedding provider unavailable"));

        initializer.onStart(new StartupEvent());

        verifyNoInteractions(jdbi);
        verify(metadata).markUnavailable(VectorIndexNamespace.RAG_GLOBAL);
        verify(metadata).markUnavailable(VectorIndexNamespace.ARCHIVAL);
    }

    // =========================================================================
    // Dimension agreement
    // =========================================================================

    @Test
    @DisplayName("dimension mismatch marks BOTH namespaces unavailable and inspects nothing")
    void dimensionMismatchMarksBothNamespacesUnavailable() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        EmbeddingIndexDescriptor stale = descriptor(CONFIGURED_DIMENSION + 1);
        givenActiveDescriptor(stale);

        initializer.onStart(new StartupEvent());

        verify(metadata).markUnavailable(VectorIndexNamespace.RAG_GLOBAL, stale);
        verify(metadata).markUnavailable(VectorIndexNamespace.ARCHIVAL, stale);
        verify(metadata, never()).verifyOrInitialize(any(), any(), anyBoolean());
        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("matching dimension verifies both whitelisted tables and reports an empty index")
    void matchingDimensionVerifiesBothNamespacesWithEmptyIndex() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        EmbeddingIndexDescriptor active = descriptor(CONFIGURED_DIMENSION);
        givenActiveDescriptor(active);
        givenTableRowCount(0);

        initializer.onStart(new StartupEvent());

        ArgumentCaptor<Boolean> indexEmpty = ArgumentCaptor.forClass(Boolean.class);
        verify(metadata, times(2)).verifyOrInitialize(any(), eq(active), indexEmpty.capture());
        assertTrue(
                indexEmpty.getAllValues().stream().allMatch(Boolean::booleanValue),
                "count==0 must be reported as an empty index");
        verify(metadata, never()).markUnavailable(any(), any());
    }

    @Test
    @DisplayName("a populated table is reported as NOT empty")
    void populatedTableIsReportedAsNotEmpty() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        EmbeddingIndexDescriptor active = descriptor(CONFIGURED_DIMENSION);
        givenActiveDescriptor(active);
        givenTableRowCount(7);

        initializer.onStart(new StartupEvent());

        ArgumentCaptor<Boolean> indexEmpty = ArgumentCaptor.forClass(Boolean.class);
        verify(metadata, times(2)).verifyOrInitialize(any(), eq(active), indexEmpty.capture());
        assertTrue(
                indexEmpty.getAllValues().stream().noneMatch(Boolean::booleanValue),
                "count>0 must be reported as a populated index");
    }

    @Test
    @DisplayName("the count query targets the whitelisted table name of each namespace, in order")
    void queryUsesWhitelistedTableNamesInNamespaceOrder() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        givenActiveDescriptor(descriptor(CONFIGURED_DIMENSION));
        givenTableRowCount(0);

        initializer.onStart(new StartupEvent());

        // The whitelist maps RAG_GLOBAL -> fts.vec_global and ARCHIVAL -> vec_archival. Asserting the
        // literal SQL is what proves the mapping is applied, rather than a raw namespace string.
        assertEquals(List.of("SELECT count(*) FROM fts.vec_global", "SELECT count(*) FROM vec_archival"), executedSql);
    }

    @Test
    @DisplayName("an inspection failure marks only that namespace unavailable and still verifies the next")
    void inspectionFailureIsIsolatedPerNamespace() {
        when(interceptor.isVectorSearchEnabled()).thenReturn(true);
        EmbeddingIndexDescriptor active = descriptor(CONFIGURED_DIMENSION);
        givenActiveDescriptor(active);
        when(jdbi.withHandle(any())).thenThrow(new IllegalStateException("no such table: fts.vec_global"));

        initializer.onStart(new StartupEvent());

        ArgumentCaptor<VectorIndexNamespace> namespaces = ArgumentCaptor.forClass(VectorIndexNamespace.class);
        verify(metadata, times(2)).markUnavailable(namespaces.capture(), eq(active));
        assertEquals(
                List.of(VectorIndexNamespace.RAG_GLOBAL, VectorIndexNamespace.ARCHIVAL),
                namespaces.getAllValues(),
                "a failure on the first namespace must not abort the second");
        verify(metadata, never()).verifyOrInitialize(any(), any(), anyBoolean());
    }
}
