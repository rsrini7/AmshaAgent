package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FsVectorIndexMetadataAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesChecksummedManifestAndPreservesItOnNonEmptyMismatch() {
        FsVectorIndexMetadataAdapter adapter = adapter();
        EmbeddingIndexDescriptor active = descriptor("revision-1");

        VectorIndexCompatibility initialized =
                adapter.verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, active, true);

        assertEquals(VectorIndexCompatibility.Status.INITIALIZED, initialized.status());
        assertTrue(initialized.vectorEnabled());
        assertTrue(Files.isRegularFile(tempDir.resolve("lucene-index/index_descriptor.json")));
        assertEquals(active, adapter.find(VectorIndexNamespace.RAG_GLOBAL).orElseThrow());

        EmbeddingIndexDescriptor changed = descriptor("revision-2");
        VectorIndexCompatibility mismatch = adapter.verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, changed, false);

        assertEquals(VectorIndexCompatibility.Status.MISMATCH, mismatch.status());
        assertFalse(mismatch.vectorEnabled());
        assertEquals(active, adapter.find(VectorIndexNamespace.RAG_GLOBAL).orElseThrow());

        VectorIndexCompatibility reset = adapter.verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, changed, true);

        assertEquals(VectorIndexCompatibility.Status.INITIALIZED, reset.status());
        assertTrue(adapter.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL));
        assertEquals(changed, adapter.find(VectorIndexNamespace.RAG_GLOBAL).orElseThrow());
        assertEquals(reset, adapter.compatibilitySnapshot().get(VectorIndexNamespace.RAG_GLOBAL));
    }

    @SuppressWarnings("UnnecessaryAssignment")
    private FsVectorIndexMetadataAdapter adapter() {
        FsVectorIndexMetadataAdapter adapter = new FsVectorIndexMetadataAdapter();
        adapter.properties = new FsStoreProperties() {
            @Override
            public String dataDir() {
                return tempDir.toString();
            }

            @Override
            public int lockTimeoutSeconds() {
                return 5;
            }

            @Override
            public String luceneIndexDir() {
                return "lucene-index";
            }

            @Override
            public int vectorDimension() {
                return 4;
            }
        };
        adapter.objectMapper = new ObjectMapper();
        return adapter;
    }

    private static EmbeddingIndexDescriptor descriptor(String revision) {
        return EmbeddingIndexDescriptor.create(
                "test",
                "test-space",
                revision,
                "tokenizer-1",
                "preprocessing-1",
                "MASKED_MEAN",
                true,
                4,
                "FLOAT32",
                "FIRST_MAX_TOKENS",
                "v1",
                512,
                0,
                "DIRECT");
    }
}
