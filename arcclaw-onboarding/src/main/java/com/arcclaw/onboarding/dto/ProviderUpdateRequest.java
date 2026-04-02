package com.arcclaw.onboarding.dto;

public record ProviderUpdateRequest(
    String provider,
    String model,
    String apiKey
) {}
