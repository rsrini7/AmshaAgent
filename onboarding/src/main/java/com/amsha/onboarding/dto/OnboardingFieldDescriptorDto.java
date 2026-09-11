package com.amsha.onboarding.dto;

import java.util.List;

/**
 * Resolved, localized field descriptor for an onboarding step.
 */
public record OnboardingFieldDescriptorDto(
        String name, String type, boolean required, List<String> options, String title, String description) {

    public OnboardingFieldDescriptorDto {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type is required");
        if (options == null) options = List.of();
    }
}
