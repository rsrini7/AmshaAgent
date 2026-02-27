package com.arcclaw.core.audit;

/**
 * Thrown when a CRITICAL (MANDATORY) audit event could not be persisted
 * after all retry attempts. Signals a fail-closed compliance violation.
 *
 * <p>When this exception appears in the call stack, the agent loop MUST halt
 * immediately — no subsequent LLM calls or tool executions are permitted
 * because the audit trail is broken.
 */
public class ComplianceViolationException extends RuntimeException {

    private final String sessionId;
    private final SecurityEventType eventType;

    public ComplianceViolationException(String sessionId, SecurityEventType eventType, Throwable cause) {
        super(String.format("[COMPLIANCE-VIOLATION] Mandatory audit write failed for session=%s eventType=%s",
                sessionId, eventType), cause);
        this.sessionId = sessionId;
        this.eventType = eventType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public SecurityEventType getEventType() {
        return eventType;
    }
}
