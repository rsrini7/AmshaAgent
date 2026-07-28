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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnnxAdminResourceTest {

    private OnnxAdminResource resource;
    private SecurityContext securityContext;
    private Instance<EmbeddingModel> embeddingModels;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resource = new OnnxAdminResource();
        securityContext = mock(SecurityContext.class);
        embeddingModels = mock(Instance.class);
        resource.securityContext = securityContext;
        resource.embeddingModel = embeddingModels;
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
}
