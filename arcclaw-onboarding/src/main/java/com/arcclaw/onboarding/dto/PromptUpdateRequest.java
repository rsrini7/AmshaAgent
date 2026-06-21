package com.arcclaw.onboarding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PromptUpdateRequest(
        @NotBlank @Size(max = 100000) String systemPrompt) {}
