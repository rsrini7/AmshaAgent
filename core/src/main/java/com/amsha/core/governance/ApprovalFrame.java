package com.amsha.core.governance;

import java.util.Map;

import com.amsha.security.api.RiskLevel;

/**
 * Typed frame for Human-In-The-Loop (HITL) approval requests.
 * Registered for native reflection centrally via {@code CoreReflectionConfig}.
 */
public record ApprovalFrame(String tool, Map<String, Object> params, RiskLevel riskLevel) {}
