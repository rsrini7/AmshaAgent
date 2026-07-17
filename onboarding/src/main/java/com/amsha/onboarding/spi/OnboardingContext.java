package com.amsha.onboarding.spi;

/**
 * Namespaced, typed safe selections and staging handles exposed by the coordinator-owned context.
 * It does not expose raw vault values, persistence handles, configuration writers, arbitrary files,
 * another extension's input, or unrestricted service lookup.
 */
public record OnboardingContext(String ownerId, String sessionId, String extensionNamespace) {

    public OnboardingContext {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        if (extensionNamespace == null || extensionNamespace.isBlank())
            throw new IllegalArgumentException("extensionNamespace is required");
    }
}
