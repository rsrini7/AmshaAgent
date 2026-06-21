package com.amsha.app.ui.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank @Size(max = 100000) String input,
        @Size(max = 255) String sessionId) {}
