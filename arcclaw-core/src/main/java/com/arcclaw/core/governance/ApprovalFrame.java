package com.arcclaw.core.governance;

import java.util.Map;

/**
 * Typed frame for Human-In-The-Loop (HITL) approval requests.
 */
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
