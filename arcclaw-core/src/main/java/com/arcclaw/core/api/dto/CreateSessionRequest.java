package com.arcclaw.core.api.dto;

import com.arcclaw.core.security.AutonomyLevel;

public record CreateSessionRequest(AutonomyLevel autonomyLevel) {
}
