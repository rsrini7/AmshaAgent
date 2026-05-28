package com.arcclaw.onboarding.dto;

import jakarta.validation.constraints.NotBlank;

public record ProviderUpdateRequest(
        @NotBlank String provider,
        @NotBlank String model,
        @NotBlank String apiKey) {}
