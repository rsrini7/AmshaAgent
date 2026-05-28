package com.arcclaw.onboarding.dto;

import jakarta.validation.constraints.NotBlank;

public record McpServerRequest(
        @NotBlank String name, @NotBlank String transport, String url, String command, String args, String env) {}
