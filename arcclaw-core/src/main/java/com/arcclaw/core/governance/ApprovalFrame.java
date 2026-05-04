package com.arcclaw.core.governance;

import java.util.Map;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Typed frame for Human-In-The-Loop (HITL) approval requests.
 */
@RegisterForReflection
public record ApprovalFrame(
    String tool,
    Map<String, Object> params,
    RiskLevel riskLevel
) {
    /**
     * Categorized risk level for the requested tool execution.
     */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}
