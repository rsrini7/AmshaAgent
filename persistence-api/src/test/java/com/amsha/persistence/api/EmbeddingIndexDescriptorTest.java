package com.amsha.persistence.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class EmbeddingIndexDescriptorTest {

    @Test
    void compatibilityIgnoresProviderTransportProvenance() {
        EmbeddingIndexDescriptor onnx = descriptor("onnx", "revision-1", "MASKED_MEAN");
        EmbeddingIndexDescriptor remote = descriptor("openai-compatible", "revision-1", "MASKED_MEAN");

        assertNotEquals(onnx.providerId(), remote.providerId());
        assertTrue(onnx.isCompatibleWith(remote));
    }

    @Test
    void semanticFieldChangesInvalidateCompatibility() {
        EmbeddingIndexDescriptor baseline = descriptor("onnx", "revision-1", "MASKED_MEAN");
        EmbeddingIndexDescriptor changedModel = descriptor("onnx", "revision-2", "MASKED_MEAN");
        EmbeddingIndexDescriptor changedPooling = descriptor("onnx", "revision-1", "CLS_TOKEN");

        assertFalse(baseline.isCompatibleWith(changedModel));
        assertFalse(baseline.isCompatibleWith(changedPooling));
    }

    @Test
    void nonEmptyMissingOrMismatchedMetadataDisablesVectors() {
        EmbeddingIndexDescriptor active = descriptor("onnx", "revision-1", "MASKED_MEAN");
        EmbeddingIndexDescriptor stale = descriptor("onnx", "revision-2", "MASKED_MEAN");

        assertFalse(VectorIndexCompatibility.assess(VectorIndexNamespace.RAG_GLOBAL, active, Optional.empty(), false)
                .vectorEnabled());
        assertFalse(VectorIndexCompatibility.assess(VectorIndexNamespace.RAG_GLOBAL, active, Optional.of(stale), false)
                .vectorEnabled());
        assertTrue(VectorIndexCompatibility.assess(VectorIndexNamespace.RAG_GLOBAL, active, Optional.empty(), true)
                .vectorEnabled());
        assertTrue(VectorIndexCompatibility.assess(VectorIndexNamespace.RAG_GLOBAL, active, Optional.of(active), false)
                .vectorEnabled());
    }

    private static EmbeddingIndexDescriptor descriptor(String providerId, String revision, String pooling) {
        return EmbeddingIndexDescriptor.create(
                providerId,
                "sentence-transformers/all-mpnet-base-v2",
                revision,
                revision,
                "onnx-transformer-v1",
                pooling,
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
