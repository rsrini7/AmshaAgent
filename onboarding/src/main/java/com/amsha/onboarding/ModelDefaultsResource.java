package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.routing.ModelDefaults;

import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * REST endpoint that serves centralized model defaults from {@link ModelDefaults}.
 * The UI uses this endpoint instead of hardcoding fallback model names.
 * This ensures a single source of truth for default model names across backend and frontend.
 */
@Path("/model-defaults")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class ModelDefaultsResource {

    @GET
    public Response getAllDefaults() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        result.put(
                ModelDefaults.PROVIDER_GROQ,
                providerEntry(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.DEFAULT_GROQ_BASE_URL));
        result.put(
                ModelDefaults.PROVIDER_OPENAI,
                providerEntry(ModelDefaults.DEFAULT_OPENAI_MODEL, ModelDefaults.DEFAULT_OPENAI_BASE_URL));
        result.put(
                ModelDefaults.PROVIDER_OLLAMA,
                providerEntry(ModelDefaults.DEFAULT_OLLAMA_MODEL, ModelDefaults.DEFAULT_OLLAMA_BASE_URL));
        result.put(
                ModelDefaults.PROVIDER_ANTHROPIC,
                providerEntry(ModelDefaults.DEFAULT_ANTHROPIC_MODEL, ModelDefaults.DEFAULT_ANTHROPIC_BASE_URL));
        result.put(ModelDefaults.PROVIDER_CLAUDE_CODE, providerEntry(ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL, ""));
        result.put(
                ModelDefaults.PROVIDER_OPENROUTER,
                providerEntry(ModelDefaults.DEFAULT_OPENROUTER_MODEL, ModelDefaults.DEFAULT_OPENROUTER_BASE_URL));
        result.put(
                ModelDefaults.PROVIDER_NVIDIA,
                providerEntry(ModelDefaults.DEFAULT_NVIDIA_MODEL, ModelDefaults.DEFAULT_NVIDIA_BASE_URL));
        result.put(
                ModelDefaults.PROVIDER_CEREBRAS,
                providerEntry(ModelDefaults.DEFAULT_CEREBRAS_MODEL, ModelDefaults.DEFAULT_CEREBRAS_BASE_URL));
        result.put(
                ModelDefaults.PROVIDER_GEMINI,
                providerEntry(ModelDefaults.DEFAULT_GEMINI_MODEL, ModelDefaults.DEFAULT_GEMINI_BASE_URL));
        result.put(
                ModelDefaults.PROVIDER_OMLX,
                providerEntry(ModelDefaults.DEFAULT_OMLX_MODEL, ModelDefaults.DEFAULT_OMLX_BASE_URL));
        result.put(
                ModelDefaults.PROVIDER_LLAMACPP,
                providerEntry(ModelDefaults.DEFAULT_LLAMACPP_MODEL, ModelDefaults.DEFAULT_LLAMACPP_BASE_URL));
        result.put(
                ModelDefaults.PROVIDER_GENERIC_OPENAI, providerEntry(ModelDefaults.DEFAULT_GENERIC_OPENAI_MODEL, ""));
        return Response.ok(result).build();
    }

    @GET
    @Path("/provider")
    public Response getDefaultForProvider(@QueryParam("id") String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("provider id is required"))
                    .build();
        }
        String model = ModelDefaults.defaultModelForProvider(providerId);
        String baseUrl = ModelDefaults.defaultBaseUrlForProvider(providerId);
        return Response.ok(providerEntry(model, baseUrl)).build();
    }

    @GET
    @Path("/infer-provider")
    public Response inferProvider(@QueryParam("baseUrl") String baseUrl) {
        String provider = ModelDefaults.inferProviderFromUrl(baseUrl);
        return Response.ok(Map.of("provider", provider)).build();
    }

    private static Map<String, String> providerEntry(String defaultModel, String defaultBaseUrl) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("defaultModel", defaultModel);
        if (!defaultBaseUrl.isBlank()) {
            entry.put("defaultBaseUrl", defaultBaseUrl);
        }
        return entry;
    }
}
