package com.arcclaw.core.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RunAgentRequest(
        @NotBlank String goal,
        @NotBlank String agentRole,
        Integer maxIterations) {
}
