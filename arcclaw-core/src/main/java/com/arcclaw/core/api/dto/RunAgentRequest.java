package com.arcclaw.core.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RunAgentRequest(
        @NotBlank String goal,
        @NotBlank String agentRole,
        @Min(value = 1, message = "maxIterations must be at least 1") Integer maxIterations) {
}