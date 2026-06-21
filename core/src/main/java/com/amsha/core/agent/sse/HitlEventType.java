package com.amsha.core.agent.sse;

/**
 * Typed constants for HITL SSE event types, replacing stringly-typed literals
 * like "AWAITING_APPROVAL", "APPROVED", "DENIED", "RESUMED".
 * <p>
 * Used as the {@code type} field in {@link AgentSseService.HitlEvent}.
 */
public enum HitlEventType {
    AWAITING_APPROVAL,
    APPROVED,
    DENIED,
    RESUMED;

    /** Returns the enum name for JSON/SSE wire format (backward-compatible). */
    public String wireValue() {
        return name();
    }
}
