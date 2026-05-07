package com.arcclaw.onboarding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Step4Request(@NotBlank @Size(max = 100000) String systemPrompt) {}
