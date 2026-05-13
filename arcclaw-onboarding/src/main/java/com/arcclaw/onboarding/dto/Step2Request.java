package com.arcclaw.onboarding.dto;

import jakarta.validation.constraints.NotBlank;

public record Step2Request(@NotBlank String provider, @NotBlank String model) {}
