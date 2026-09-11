package com.amsha.onboarding.dto;

import java.util.List;

/**
 * Resolved, localized step descriptor returned to the UI.
 */
public record OnboardingStepDescriptorDto(
        String id,
        String version,
        int order,
        boolean optional,
        String title,
        String description,
        List<OnboardingFieldDescriptorDto> fields) {

    public OnboardingStepDescriptorDto {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (fields == null) fields = List.of();
    }
}
