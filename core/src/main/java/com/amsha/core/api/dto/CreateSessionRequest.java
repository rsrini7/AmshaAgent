package com.amsha.core.api.dto;

import jakarta.validation.constraints.NotNull;

import com.amsha.security.api.AutonomyLevel;

public record CreateSessionRequest(@NotNull AutonomyLevel autonomyLevel) {}
