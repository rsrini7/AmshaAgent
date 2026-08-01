package com.amsha.app.api;

import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Classifier;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Snapshot;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.ToggleResult;
import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.EmbeddingIndexDescriptorSource;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * Internal-only diagnostics for the opt-in ONNX embedding path.
 *
 * <p>The probe uses a fixed, non-sensitive string and returns only derived vector
 * properties. It never accepts caller text and never exposes an embedding.
 */
@ApplicationScoped
@Path("/admin/local-ml/onnx")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class OnnxAdminResource {

    private static final String PROBE_TEXT = "Amsha local ONNX diagnostic probe.";

    @Inject
    Instance<EmbeddingModel> embeddingModel;

    @Inject
    Instance<EmbeddingIndexDescriptorSource> descriptorSources;

    @Inject
    VectorIndexMetadataPort vectorIndexMetadata;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    OnnxClassifierRuntimeControl classifierRuntimeControl;

    @Inject
    AuditService auditService;

    @ConfigProperty(name = "amsha.onnx.enabled", defaultValue = "false")
    boolean onnxEnabled;

    @ConfigProperty(name = "amsha.infinite-loop.embedding.providers", defaultValue = "ollama")
    String embeddingProviders;

    @Context
    SecurityContext securityContext;

    /** Executes one bounded, fixed-text embedding to verify the configured ONNX slot. */
    @POST
    @Path("/probe")
    public Response probe() {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can run the ONNX diagnostic probe"))
                    .build();
        }
        recordProbeStage("authorized");
        if (!onnxEnabled || !onnxConfigured()) {
            return unavailable("ONNX embedding is not explicitly configured");
        }
        recordProbeStage("configuration_verified");
        recordProbeStage("model_resolution_started");
        if (embeddingModel == null || embeddingModel.isUnsatisfied()) {
            return unavailable("No embedding model is available");
        }
        recordProbeStage("model_resolution_ready");

        try {
            recordProbeStage("model_lookup_started");
            EmbeddingModel model = embeddingModel.get();
            recordProbeStage("model_lookup_ready");
            recordProbeStage("embedding_started");
            float[] vector = model.embed(PROBE_TEXT).content().vector();
            recordProbeStage("embedding_completed");
            if (vector == null || vector.length == 0 || !allFinite(vector)) {
                return unavailable("ONNX embedding probe returned an invalid vector");
            }
            return Response.ok(Map.of("status", "ready", "dimensions", vector.length, "l2Norm", l2Norm(vector)))
                    .build();
        } catch (RuntimeException e) {
            return unavailable("ONNX embedding probe failed: " + LogUtil.rootMessage(e));
        }
    }

    /** Returns protected, exact descriptor diagnostics without exposing vectors or inference input. */
    @GET
    @Path("/indexes")
    public Response indexCompatibility() {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can inspect ONNX index descriptors"))
                    .build();
        }
        if (!onnxEnabled || !onnxConfigured()) {
            return unavailable("ONNX embedding is not explicitly configured");
        }
        if (descriptorSources == null
                || vectorIndexMetadata == null
                || descriptorSources.isUnsatisfied()
                || descriptorSources.isAmbiguous()) {
            return unavailable("Embedding index descriptor diagnostics are unavailable");
        }
        EmbeddingIndexDescriptor descriptor =
                descriptorSources.get().activeDescriptor().orElse(null);
        if (descriptor == null) {
            return unavailable("No active embedding index descriptor is available");
        }

        Map<VectorIndexNamespace, VectorIndexCompatibility> snapshot = vectorIndexMetadata.compatibilitySnapshot();
        boolean degraded = snapshot.values().stream().anyMatch(result -> !result.vectorEnabled());
        String status = snapshot.isEmpty() ? "not-active" : degraded ? "degraded" : "ready";

        Map<String, Object> namespaces = new LinkedHashMap<>();
        snapshot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(VectorIndexNamespace::persistentId)))
                .forEach(
                        entry -> namespaces.put(entry.getKey().persistentId(), namespaceDiagnostics(entry.getValue())));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("activeDescriptor", descriptorDiagnostics(descriptor));
        payload.put("namespaces", namespaces);
        return Response.ok(payload).build();
    }

    /** Returns the protected, payload-free state of all Phase 3 classifier switches. */
    @GET
    @Path("/classifiers")
    public Response classifierRuntimeStatus() {
        if (!isInternal()) {
            return classifierControlForbidden();
        }
        return Response.ok(new ClassifierRuntimeResponse(classifierRuntimeControl.snapshots()))
                .build();
    }

    /** Enables one classifier only when a ready active implementation has registered. */
    @POST
    @Path("/classifiers/{classifier}/enable")
    public Response enableClassifier(@PathParam("classifier") String classifierId) {
        return updateClassifier(classifierId, true);
    }

    /** Disables one classifier immediately for new requests while existing leases drain. */
    @POST
    @Path("/classifiers/{classifier}/disable")
    public Response disableClassifier(@PathParam("classifier") String classifierId) {
        return updateClassifier(classifierId, false);
    }

    private Response updateClassifier(String classifierId, boolean enabled) {
        if (!isInternal()) {
            return classifierControlForbidden();
        }
        Classifier classifier = Classifier.fromId(classifierId).orElse(null);
        if (classifier == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse(
                            "unknown_classifier", "Unknown classifier; expected intent, tool-risk, or answer-quality"))
                    .build();
        }

        Snapshot before = classifierRuntimeControl.snapshot(classifier);
        if (enabled && !before.implementationAvailable()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ClassifierToggleResponse(false, false, "MISSING_READY_IMPLEMENTATION", before))
                    .build();
        }
        if (before.requestedEnabled() == enabled) {
            return Response.ok(new ClassifierToggleResponse(true, false, "UNCHANGED", before))
                    .build();
        }

        auditService.recordMandatory(
                "local-ml-classifier:" + classifier.id(),
                resolveUserId(),
                "system",
                SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_REQUESTED,
                "classifier=" + classifier.id() + " requestedEnabled=" + enabled);

        ToggleResult result = classifierRuntimeControl.setEnabled(classifier, enabled);
        auditService.recordMandatory(
                "local-ml-classifier:" + classifier.id(),
                resolveUserId(),
                "system",
                result.accepted()
                        ? SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_ACCEPTED
                        : SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_REJECTED,
                "classifier=" + classifier.id() + " requestedEnabled=" + enabled + " reason=" + result.reason());
        ClassifierToggleResponse response =
                new ClassifierToggleResponse(result.accepted(), result.changed(), result.reason(), result.snapshot());
        return result.accepted()
                ? Response.ok(response).build()
                : Response.status(Response.Status.CONFLICT).entity(response).build();
    }

    private boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole("internal");
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    private boolean onnxConfigured() {
        return embeddingProviders != null
                && Arrays.stream(embeddingProviders.split(","))
                        .map(provider -> provider.trim().toLowerCase(Locale.ROOT))
                        .anyMatch("onnx"::equals);
    }

    private static boolean allFinite(float[] vector) {
        for (float value : vector) {
            if (!Float.isFinite(value)) return false;
        }
        return true;
    }

    private static double l2Norm(float[] vector) {
        double sum = 0;
        for (float value : vector) sum += value * value;
        return Math.sqrt(sum);
    }

    private static Response unavailable(String message) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(new AmshaErrorResponse("onnx_probe_unavailable", message))
                .build();
    }

    private static Map<String, Object> descriptorDiagnostics(EmbeddingIndexDescriptor descriptor) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("providerId", descriptor.providerId());
        diagnostics.put("spaceId", descriptor.spaceId());
        diagnostics.put("modelRevision", descriptor.modelRevision());
        diagnostics.put("tokenizerRevision", descriptor.tokenizerRevision());
        diagnostics.put("preprocessingRevision", descriptor.preprocessingRevision());
        diagnostics.put("poolingStrategy", descriptor.poolingStrategy());
        diagnostics.put("l2Normalize", descriptor.l2Normalize());
        diagnostics.put("vectorDimension", descriptor.vectorDimension());
        diagnostics.put("numericPrecision", descriptor.numericPrecision());
        diagnostics.put("chunkingStrategy", descriptor.chunkingStrategy());
        diagnostics.put("chunkingRevision", descriptor.chunkingRevision());
        diagnostics.put("maxWindowTokens", descriptor.maxWindowTokens());
        diagnostics.put("windowOverlapTokens", descriptor.windowOverlapTokens());
        diagnostics.put("multiWindowAggregation", descriptor.multiWindowAggregation());
        diagnostics.put("fingerprint", descriptor.fingerprint());
        return diagnostics;
    }

    private static Map<String, Object> namespaceDiagnostics(VectorIndexCompatibility compatibility) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("status", compatibility.status().name());
        diagnostics.put("vectorEnabled", compatibility.vectorEnabled());
        diagnostics.put("activeFingerprint", compatibility.activeFingerprint());
        diagnostics.put("storedFingerprint", compatibility.storedFingerprint().orElse("missing"));
        return diagnostics;
    }

    private void recordProbeStage(String stage) {
        if (meterRegistry == null) return;
        Counter.builder("amsha.onnx.probe.stage")
                .tag("stage", stage)
                .description("Payload-free checkpoints for the internal ONNX diagnostic probe")
                .register(meterRegistry)
                .increment();
    }

    private static Response classifierControlForbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new AmshaErrorResponse("Only internal API keys can control ONNX classifiers"))
                .build();
    }

    public record ClassifierRuntimeResponse(List<Snapshot> classifiers) {
        public ClassifierRuntimeResponse {
            classifiers = List.copyOf(classifiers);
        }
    }

    public record ClassifierToggleResponse(boolean accepted, boolean changed, String reason, Snapshot classifier) {}
}
