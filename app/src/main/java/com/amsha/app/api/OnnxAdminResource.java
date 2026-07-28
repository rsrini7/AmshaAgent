package com.amsha.app.api;

import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.api.dto.AmshaErrorResponse;

import org.eclipse.microprofile.config.inject.ConfigProperty;

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
        if (!onnxEnabled || !onnxConfigured()) {
            return unavailable("ONNX embedding is not explicitly configured");
        }
        if (embeddingModel == null || embeddingModel.isUnsatisfied()) {
            return unavailable("No embedding model is available");
        }

        try {
            float[] vector = embeddingModel.get().embed(PROBE_TEXT).content().vector();
            if (vector == null || vector.length == 0 || !allFinite(vector)) {
                return unavailable("ONNX embedding probe returned an invalid vector");
            }
            return Response.ok(Map.of("status", "ready", "dimensions", vector.length, "l2Norm", l2Norm(vector)))
                    .build();
        } catch (RuntimeException e) {
            return unavailable("ONNX embedding probe failed: " + LogUtil.rootMessage(e));
        }
    }

    private boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole("internal");
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
}
