package com.amsha.security.api;

/**
 * Categorized risk level for a requested tool execution, carried on HITL approval
 * frames and tool-operation metadata. Extracted from core governance so the security
 * vocabulary is self-contained (extract-core-subsystems S4).
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
