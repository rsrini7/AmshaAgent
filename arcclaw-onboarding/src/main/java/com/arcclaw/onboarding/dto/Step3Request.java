package com.arcclaw.onboarding.dto;

import jakarta.validation.constraints.NotBlank;

public record Step3Request(@NotBlank String apiKey) {}
