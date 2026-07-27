package com.amsha.onboarding.spi;

import java.util.List;

/**
 * Stable, declarative descriptor for an onboarding extension stage. Not arbitrary JSON Schema.
 */
public record OnboardingStepDescriptor(
        String extensionId,
        String extensionVersion,
        int order,
        boolean optional,
        List<OnboardingFieldDescriptor> fields,
        String titleKey,
        String descriptionKey) {

    public OnboardingStepDescriptor {
        if (extensionId == null || extensionId.isBlank()) throw new IllegalArgumentException("extensionId is required");
        if (fields == null) throw new IllegalArgumentException("fields is required");
    }
}
