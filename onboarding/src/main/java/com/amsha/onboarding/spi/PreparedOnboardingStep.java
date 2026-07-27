package com.amsha.onboarding.spi;

/**
 * Immutable, serializable safe-data snapshot passed explicitly to validation and planning. An
 * extension must not retain mutable request/draft state in bean fields; the same prepared snapshot
 * must yield deterministic results across retries, restarts, and different CDI-resolved instances.
 */
public record PreparedOnboardingStep(String extensionId, String extensionVersion, String fingerprint, byte[] safeData) {

    public PreparedOnboardingStep {
        if (extensionId == null || extensionId.isBlank()) throw new IllegalArgumentException("extensionId is required");
        if (fingerprint == null || fingerprint.isBlank()) throw new IllegalArgumentException("fingerprint is required");
    }
}
