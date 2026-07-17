package com.amsha.onboarding.spi;

import java.util.List;

/**
 * Declarative descriptor for one typed onboarding field. Bounded static options only; no free-form
 * schema, no client-side validation code, no arbitrary regular expressions.
 */
public record OnboardingFieldDescriptor(
        String name,
        OnboardingFieldType type,
        boolean required,
        List<String> options,
        String titleKey,
        String descriptionKey) {

    public OnboardingFieldDescriptor {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (type == null) throw new IllegalArgumentException("type is required");
    }
}
