package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.amsha.persistence.sqlite.jdbi.VectorIndexMetadataJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit coverage for {@link SqliteVectorIndexMetadataAdapter}'s degraded arms.
 *
 * <p>Why a separate class: {@link SqliteVectorIndexMetadataAdapterTest} is a {@code @QuarkusTest} against a
 * migrated database, so {@code vector_index_metadata} always exists and the metadata-read failure arm can
 * never fire there. The {@code markUnavailable} path is likewise only reachable when an upstream
 * initializer gives up, which no integration test drives.
 */
class SqliteVectorIndexMetadataAdapterDegradedTest {

    private Jdbi jdbi;
    private SqliteVectorIndexMetadataAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        adapter = new SqliteVectorIndexMetadataAdapter();
        adapter.jdbi = jdbi;
    }

    private static EmbeddingIndexDescriptor descriptor() {
        return EmbeddingIndexDescriptor.create(
                "onnx",
                "sentence-transformers/all-mpnet-base-v2",
                "revision-1",
                "revision-1",
                "onnx-transformer-v1",
                "MASKED_MEAN",
                true,
                768,
                "FLOAT32",
                "FIRST_MAX_TOKENS",
                "v1",
                512,
                0,
                "DIRECT");
    }

    @Test
    @DisplayName("a metadata read failure degrades to UNAVAILABLE instead of propagating")
    void metadataReadFailureDegradesToUnavailable() {
        // Explicit type witness keeps the inferred checked-exception variable at RuntimeException, so
        // thenThrow needs no Answer wrapper.
        when(jdbi.<Optional<EmbeddingIndexDescriptor>, VectorIndexMetadataJdbiDao, RuntimeException>withExtension(
                        eq(VectorIndexMetadataJdbiDao.class), any()))
                .thenThrow(new IllegalStateException("no such table: vector_index_metadata"));

        VectorIndexCompatibility result =
                adapter.verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, descriptor(), true);

        assertEquals(VectorIndexCompatibility.Status.UNAVAILABLE, result.status());
        assertFalse(result.vectorEnabled());
        assertTrue(result.storedFingerprint().isEmpty(), "a degraded namespace must report no stored fingerprint");
        assertEquals(result, adapter.compatibilitySnapshot().get(VectorIndexNamespace.RAG_GLOBAL));
        assertFalse(adapter.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL));
    }

    @Test
    @DisplayName("markUnavailable records an UNAVAILABLE snapshot for a namespace never verified")
    void markUnavailableRecordsAnUnavailableSnapshot() {
        VectorIndexCompatibility result = adapter.markUnavailable(VectorIndexNamespace.ARCHIVAL, descriptor());

        assertEquals(VectorIndexCompatibility.Status.UNAVAILABLE, result.status());
        assertEquals(VectorIndexNamespace.ARCHIVAL, result.namespace());
        assertFalse(result.vectorEnabled());
        assertTrue(result.storedFingerprint().isEmpty());
        assertEquals(result, adapter.compatibilitySnapshot().get(VectorIndexNamespace.ARCHIVAL));
        assertFalse(adapter.isVectorEnabled(VectorIndexNamespace.ARCHIVAL));
    }

    @Test
    @DisplayName("a namespace with no recorded compatibility is not vector-enabled")
    void unknownNamespaceIsNotVectorEnabled() {
        assertFalse(adapter.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL));
        assertTrue(adapter.compatibilitySnapshot().isEmpty());
    }
}
