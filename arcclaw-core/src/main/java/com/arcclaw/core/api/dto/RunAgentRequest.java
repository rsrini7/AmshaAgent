package com.arcclaw.core.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RunAgentRequest(
        @NotBlank @Size(max = 100000) String goal,

        @NotBlank @Pattern(regexp = "^[a-z0-9:-]+$", message = "Invalid agent role format")
        String agentRole,

        @Min(value = 1, message = "maxIterations must be at least 1")
        Integer maxIterations) {}
