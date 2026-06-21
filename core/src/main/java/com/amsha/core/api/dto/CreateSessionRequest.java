package com.amsha.core.api.dto;

import jakarta.validation.constraints.NotNull;

import com.amsha.core.security.AutonomyLevel;

public record CreateSessionRequest(@NotNull AutonomyLevel autonomyLevel) {}
