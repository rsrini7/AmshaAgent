package com.amsha.onboarding.spi;

/**
 * Typed input supplied to an extension during validation. Holds only non-secret, namespaced values.
 */
public record OnboardingInput(String extensionNamespace, String jsonPayload) {

    public OnboardingInput {
        if (extensionNamespace == null || extensionNamespace.isBlank())
            throw new IllegalArgumentException("extensionNamespace is required");
    }
}
