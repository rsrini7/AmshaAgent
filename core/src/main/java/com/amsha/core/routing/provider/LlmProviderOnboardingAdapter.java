package com.amsha.core.routing.provider;

import java.util.List;
import java.util.Optional;

/**
 * SPI for surfacing provider/model onboarding suggestions and performing tiered provider validation.
 *
 * <p>Per the onboarding ADR, all provider/model suggestions presented by the wizard must come only
 * from this adapter through the shared descriptor API; the duplicated UI defaults and
 * provider-specific fallbacks are removed. Implementations are discovered as CDI beans; the core
 * ships a {@code StaticProviderCatalogAdapter} backed by {@code provider-catalog.json}.</p>
 */
public interface LlmProviderOnboardingAdapter {

    /** Returns the providers this adapter can onboard, in display order. */
    List<LlmProviderDescriptor> descriptors();

    /** Returns a single provider descriptor by id (case-insensitive). */
    default Optional<LlmProviderDescriptor> descriptor(String providerId) {
        if (providerId == null) return Optional.empty();
        String needle = providerId.toLowerCase();
        return descriptors().stream()
                .filter(d -> d.id().equalsIgnoreCase(providerId) || d.id().equalsIgnoreCase(needle))
                .findFirst();
    }

    /** Resolves an advertised model for a provider, if present. */
    default Optional<ModelEntry> resolveModel(String providerId, String modelId) {
        return descriptor(providerId).flatMap(d -> d.findModel(modelId));
    }

    /**
     * Tiered validation of an onboarding connection selection. Returns a result describing whether
     * the provider/model combination is eligible, with safe field errors when it is not. This is the
     * local, non-secret-field validation stage of the onboarding saga (stage 1).
     */
    ProviderValidationResult validate(ConnectionValidationRequest request);
}
