package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.SecurityContext;

import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Classifier;
import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.EmbeddingIndexDescriptorSource;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OnnxAdminResourceTest {

    private OnnxAdminResource resource;
    private SecurityContext securityContext;
    private Instance<EmbeddingModel> embeddingModels;
    private Instance<EmbeddingIndexDescriptorSource> descriptorSources;
    private VectorIndexMetadataPort vectorIndexMetadata;
    private OnnxClassifierRuntimeControl classifierRuntimeControl;
    private AuditService auditService;

    @BeforeEach
    @SuppressWarnings({"unchecked", "UnnecessaryAssignment"})
    void setUp() {
        resource = new OnnxAdminResource();
        securityContext = mock(SecurityContext.class);
        embeddingModels = mock(Instance.class);
        descriptorSources = mock(Instance.class);
        vectorIndexMetadata = mock(VectorIndexMetadataPort.class);
        classifierRuntimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        auditService = mock(AuditService.class);
        resource.securityContext = securityContext;
        resource.embeddingModel = embeddingModels;
        resource.descriptorSources = descriptorSources;
        resource.vectorIndexMetadata = vectorIndexMetadata;
        resource.classifierRuntimeControl = classifierRuntimeControl;
        resource.auditService = auditService;
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

    @Test
    void classifierStatusRequiresInternalCaller() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        assertEquals(403, resource.classifierRuntimeStatus().getStatus());
        verifyNoInteractions(auditService);
    }

    @Test
    void classifierStatusReportsAllUnavailableWithoutActivatingBehavior() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        var response = resource.classifierRuntimeStatus();

        assertEquals(200, response.getStatus());
        var payload = (OnnxAdminResource.ClassifierRuntimeResponse) response.getEntity();
        assertEquals(3, payload.classifiers().size());
        assertTrue(payload.classifiers().stream().noneMatch(snapshot -> snapshot.effectiveEnabled()));
        verifyNoInteractions(auditService);
    }

    @Test
    void enableRejectsMissingActiveImplementationWithoutAudit() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        var response = resource.enableClassifier("tool-risk");

        assertEquals(409, response.getStatus());
        assertFalse(classifierRuntimeControl.snapshot(Classifier.TOOL_RISK).requestedEnabled());
        verifyNoInteractions(auditService);
    }

    @Test
    void classifierMutationRequiresInternalCaller() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        assertEquals(403, resource.disableClassifier("intent").getStatus());
        verifyNoInteractions(auditService);
    }

    @Test
    void enableReadyImplementationUsesMandatoryAuditBeforeMutation() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(securityContext.getUserPrincipal()).thenReturn(() -> "internal-admin");

        try (var _ = classifierRuntimeControl.registerReadyImplementation(Classifier.TOOL_RISK)) {
            var response = resource.enableClassifier("tool-risk");

            assertEquals(200, response.getStatus());
            assertTrue(classifierRuntimeControl.snapshot(Classifier.TOOL_RISK).effectiveEnabled());
            var inOrder = Mockito.inOrder(auditService);
            inOrder.verify(auditService)
                    .recordMandatory(
                            eq("local-ml-classifier:tool-risk"),
                            eq("internal-admin"),
                            eq("system"),
                            eq(SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_REQUESTED),
                            eq("classifier=tool-risk requestedEnabled=true"));
            inOrder.verify(auditService)
                    .recordMandatory(
                            eq("local-ml-classifier:tool-risk"),
                            eq("internal-admin"),
                            eq("system"),
                            eq(SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_ACCEPTED),
                            eq("classifier=tool-risk requestedEnabled=true reason=UPDATED"));
        }
    }

    @Test
    void mandatoryAuditFailureLeavesRuntimeStateUnchanged() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService)
                .recordMandatory(
                        anyString(),
                        anyString(),
                        anyString(),
                        eq(SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_REQUESTED),
                        anyString());

        try (var _ = classifierRuntimeControl.registerReadyImplementation(Classifier.ANSWER_QUALITY)) {
            assertThrows(IllegalStateException.class, () -> resource.enableClassifier("answer-quality"));
            assertFalse(
                    classifierRuntimeControl.snapshot(Classifier.ANSWER_QUALITY).requestedEnabled());
        }
    }

    @Test
    void disableAuditsAndLeavesAcceptedLeaseDraining() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(securityContext.getUserPrincipal()).thenReturn(() -> "internal-admin");

        try (var _ = classifierRuntimeControl.registerReadyImplementation(Classifier.INTENT)) {
            classifierRuntimeControl.setEnabled(Classifier.INTENT, true);
            try (var _ = classifierRuntimeControl.tryAcquire(Classifier.INTENT).orElseThrow()) {
                var response = resource.disableClassifier("intent");

                assertEquals(200, response.getStatus());
                assertTrue(classifierRuntimeControl.snapshot(Classifier.INTENT).draining());
                assertTrue(
                        classifierRuntimeControl.tryAcquire(Classifier.INTENT).isEmpty());
                verify(auditService)
                        .recordMandatory(
                                eq("local-ml-classifier:intent"),
                                eq("internal-admin"),
                                eq("system"),
                                eq(SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_REQUESTED),
                                eq("classifier=intent requestedEnabled=false"));
                verify(auditService)
                        .recordMandatory(
                                eq("local-ml-classifier:intent"),
                                eq("internal-admin"),
                                eq("system"),
                                eq(SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_ACCEPTED),
                                eq("classifier=intent requestedEnabled=false reason=UPDATED"));
            }
            assertFalse(classifierRuntimeControl.snapshot(Classifier.INTENT).draining());
        }
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
