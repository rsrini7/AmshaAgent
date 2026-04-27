package com.arcclaw.core.api.dto;

import jakarta.validation.constraints.NotNull;

import com.arcclaw.core.security.AutonomyLevel;

public record CreateSessionRequest(@NotNull AutonomyLevel autonomyLevel) {
}
