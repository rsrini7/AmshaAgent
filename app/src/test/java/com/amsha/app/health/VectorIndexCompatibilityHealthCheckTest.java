package com.amsha.app.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;

import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

class VectorIndexCompatibilityHealthCheckTest {

    @Test
    void reportsMismatchAsDegradedWithoutFailingReadiness() {
        EmbeddingIndexDescriptor descriptor = descriptor();
        VectorIndexCompatibility mismatch = new VectorIndexCompatibility(
                VectorIndexNamespace.RAG_GLOBAL,
                VectorIndexCompatibility.Status.MISMATCH,
                descriptor.fingerprint(),
                Optional.of("0".repeat(64)));
        HealthCheckResponse response = new VectorIndexCompatibilityHealthCheck(
                        metadata(Map.of(VectorIndexNamespace.RAG_GLOBAL, mismatch)))
                .call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals("DEGRADED", data.get("vectorStatus"));
        assertEquals("MISMATCH", data.get("namespace.rag-global"));
    }

    @Test
    void reportsNotActiveWhenNoVectorNamespaceWasEvaluated() {
        HealthCheckResponse response = new VectorIndexCompatibilityHealthCheck(metadata(Map.of())).call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("NOT_ACTIVE", response.getData().orElseThrow().get("vectorStatus"));
    }

    private static VectorIndexMetadataPort metadata(Map<VectorIndexNamespace, VectorIndexCompatibility> snapshot) {
        return new VectorIndexMetadataPort() {
            @Override
            public VectorIndexCompatibility verifyOrInitialize(
                    VectorIndexNamespace namespace, EmbeddingIndexDescriptor activeDescriptor, boolean indexEmpty) {
                throw new UnsupportedOperationException();
            }

            @Override
            public VectorIndexCompatibility markUnavailable(
                    VectorIndexNamespace namespace, EmbeddingIndexDescriptor activeDescriptor) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<EmbeddingIndexDescriptor> find(VectorIndexNamespace namespace) {
                return Optional.empty();
            }

            @Override
            public boolean isVectorEnabled(VectorIndexNamespace namespace) {
                VectorIndexCompatibility result = snapshot.get(namespace);
                return result != null && result.vectorEnabled();
            }

            @Override
            public Map<VectorIndexNamespace, VectorIndexCompatibility> compatibilitySnapshot() {
                return snapshot;
            }
        };
    }

    private static EmbeddingIndexDescriptor descriptor() {
        return EmbeddingIndexDescriptor.create(
                "onnx",
                "sentence-transformers/all-mpnet-base-v2",
                "model-r1",
                "tokenizer-r1",
                "pre-v1",
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
