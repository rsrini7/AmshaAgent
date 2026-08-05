package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.amsha.persistence.postgres.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@QuarkusTestResource(PostgresTestResource.class)
class PostgresVectorIndexMetadataAdapterTest {

    @Inject
    PostgresVectorIndexMetadataAdapter adapter;

    @Inject
    Jdbi jdbi;

    @BeforeEach
    void clearMetadata() {
        jdbi.useHandle(handle -> handle.execute("DELETE FROM vector_index_metadata"));
    }

    @Test
    void initializesEmptyNamespaceAndDoesNotOverwriteNonEmptyMismatch() {
        EmbeddingIndexDescriptor active = descriptor("revision-1");
        VectorIndexCompatibility initialized =
                adapter.verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, active, true);

        assertEquals(VectorIndexCompatibility.Status.INITIALIZED, initialized.status());
        assertTrue(adapter.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL));
        assertEquals(active, adapter.find(VectorIndexNamespace.RAG_GLOBAL).orElseThrow());

        EmbeddingIndexDescriptor changed = descriptor("revision-2");
        VectorIndexCompatibility mismatch = adapter.verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, changed, false);

        assertEquals(VectorIndexCompatibility.Status.MISMATCH, mismatch.status());
        assertFalse(adapter.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL));
        assertEquals(active, adapter.find(VectorIndexNamespace.RAG_GLOBAL).orElseThrow());

        VectorIndexCompatibility reset = adapter.verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, changed, true);

        assertEquals(VectorIndexCompatibility.Status.INITIALIZED, reset.status());
        assertTrue(adapter.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL));
        assertEquals(changed, adapter.find(VectorIndexNamespace.RAG_GLOBAL).orElseThrow());
        assertEquals(reset, adapter.compatibilitySnapshot().get(VectorIndexNamespace.RAG_GLOBAL));
    }

    private static EmbeddingIndexDescriptor descriptor(String revision) {
        return EmbeddingIndexDescriptor.create(
                "onnx",
                "sentence-transformers/all-mpnet-base-v2",
                revision,
                revision,
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
}
