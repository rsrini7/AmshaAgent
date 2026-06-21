package com.amsha.onboarding.dto;

import jakarta.validation.constraints.NotBlank;

public record Step3Request(@NotBlank String apiKey) {}
