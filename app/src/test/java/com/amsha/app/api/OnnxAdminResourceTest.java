package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.SecurityContext;

import java.util.Map;
import java.util.Optional;

import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.EmbeddingIndexDescriptorSource;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnnxAdminResourceTest {

    private OnnxAdminResource resource;
    private SecurityContext securityContext;
    private Instance<EmbeddingModel> embeddingModels;
    private Instance<EmbeddingIndexDescriptorSource> descriptorSources;
    private VectorIndexMetadataPort vectorIndexMetadata;

    @BeforeEach
    @SuppressWarnings({"unchecked", "UnnecessaryAssignment"})
    void setUp() {
        resource = new OnnxAdminResource();
        securityContext = mock(SecurityContext.class);
        embeddingModels = mock(Instance.class);
        descriptorSources = mock(Instance.class);
        vectorIndexMetadata = mock(VectorIndexMetadataPort.class);
        resource.securityContext = securityContext;
        resource.embeddingModel = embeddingModels;
        resource.descriptorSources = descriptorSources;
        resource.vectorIndexMetadata = vectorIndexMetadata;
        resource.onnxEnabled = true;
        resource.embeddingProviders = "onnx";
    }

    @Test
    void probeRejectsNonInternalCallerWithoutEmbedding() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        var response = resource.probe();

        assertEquals(403, response.getStatus());
        verifyNoInteractions(embeddingModels);
    }

    @Test
    void probeRejectsDisabledOnnxWithoutEmbedding() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        resource.onnxEnabled = false;

        var response = resource.probe();

        assertEquals(503, response.getStatus());
        verifyNoInteractions(embeddingModels);
    }

    @Test
    void probeReturnsOnlyDerivedVectorProperties() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(embeddingModels.isUnsatisfied()).thenReturn(false);
        when(embeddingModels.get()).thenReturn(model);
        when(model.embed("Amsha local ONNX diagnostic probe."))
                .thenReturn(Response.from(new Embedding(new float[] {0.6f, 0.8f})));

        var response = resource.probe();

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals("ready", payload.get("status"));
        assertEquals(2, payload.get("dimensions"));
        assertEquals(1.0d, (double) payload.get("l2Norm"), 0.000001d);
        assertTrue(!payload.containsKey("vector"));
    }

    @Test
    void probeRejectsNonFiniteVector() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(embeddingModels.isUnsatisfied()).thenReturn(false);
        when(embeddingModels.get()).thenReturn(model);
        when(model.embed("Amsha local ONNX diagnostic probe."))
                .thenReturn(Response.from(new Embedding(new float[] {Float.NaN})));

        assertEquals(503, resource.probe().getStatus());
    }

    @Test
    void indexDiagnosticsExposeProtectedExactFingerprintAndDegradedNamespace() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        EmbeddingIndexDescriptor descriptor = descriptor();
        EmbeddingIndexDescriptorSource source = () -> Optional.of(descriptor);
        when(descriptorSources.isUnsatisfied()).thenReturn(false);
        when(descriptorSources.isAmbiguous()).thenReturn(false);
        when(descriptorSources.get()).thenReturn(source);
        when(vectorIndexMetadata.compatibilitySnapshot())
                .thenReturn(Map.of(
                        VectorIndexNamespace.RAG_GLOBAL,
                        new VectorIndexCompatibility(
                                VectorIndexNamespace.RAG_GLOBAL,
                                VectorIndexCompatibility.Status.MISMATCH,
                                descriptor.fingerprint(),
                                Optional.of("0".repeat(64)))));

        var response = resource.indexCompatibility();

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals("degraded", payload.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> activeDescriptor = (Map<String, Object>) payload.get("activeDescriptor");
        assertEquals(descriptor.fingerprint(), activeDescriptor.get("fingerprint"));
        assertEquals("model-r1", activeDescriptor.get("modelRevision"));
        verifyNoInteractions(embeddingModels);
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
