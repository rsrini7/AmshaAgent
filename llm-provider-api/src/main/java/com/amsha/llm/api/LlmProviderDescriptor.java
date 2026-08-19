package com.amsha.llm.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, validated description of an LLM provider as surfaced to onboarding. All provider/model
 * suggestions presented by the wizard must originate from a {@link LlmProviderOnboardingAdapter}
 * through this descriptor API (ADR: provider/model suggestions come only from the adapter, never
 * from duplicated UI defaults).
 *
 * <p>The record validates cross-field rules in its compact constructor: id and display name are
 * required, an API-key provider must declare an auth scheme, and the model catalog (if present) must
 * be non-empty and de-duplicated by model id.</p>
 */
public record LlmProviderDescriptor(
        String id,
        String displayName,
        String i18nKey,
        String authScheme,
        String defaultBaseUrl,
        boolean requiresApiKey,
        boolean embeddingCapable,
        List<ModelEntry> modelCatalog,
        Map<String, String> attributes) {

    public LlmProviderDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("provider id is required");
        if (displayName == null || displayName.isBlank()) displayName = id;
        if (i18nKey == null || i18nKey.isBlank()) i18nKey = id;
        if (authScheme == null) authScheme = requiresApiKey ? "API_KEY" : "NONE";
        if (modelCatalog == null) modelCatalog = List.of();
        if (modelCatalog.isEmpty() && requiresApiKey) {
            throw new IllegalArgumentException(
                    "provider " + id + " requires an API key and must advertise at least one model");
        }
        // De-duplicate by model id, preserving order.
        modelCatalog = modelCatalog.stream().distinct().toList();
        if (attributes == null) attributes = Map.of();
        else attributes = Map.copyOf(attributes);
    }

    /** Returns the catalog model with the given id, if advertised. */
    public Optional<ModelEntry> findModel(String modelId) {
        if (modelId == null) return Optional.empty();
        return modelCatalog.stream().filter(m -> m.id().equals(modelId)).findFirst();
    }

    /** True when the provider advertises the model id (case-sensitive). */
    public boolean supportsModel(String modelId) {
        return findModel(modelId).isPresent();
    }
}
