package com.amsha.core.governance;

import java.util.Map;

import com.amsha.security.api.RiskLevel;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Typed frame for Human-In-The-Loop (HITL) approval requests.
 */
@RegisterForReflection
public record ApprovalFrame(String tool, Map<String, Object> params, RiskLevel riskLevel) {}
