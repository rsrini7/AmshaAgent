package com.amsha.persistence.api;

/**
 * Audit event kinds owned by the tool attempt settlement subsystem per Proposal §12.4.
 *
 * <ul>
 *   <li>{@link #ATTEMPT_ADMITTED}: Attempt admitted and allocated.</li>
 *   <li>{@link #LAUNCH_REQUESTED}: Execution launch requested.</li>
 *   <li>{@link #ATTEMPT_SETTLED}: Attempt reached terminal outcome.</li>
 *   <li>{@link #ACTION_LOCKED}: Durable action lock acquired.</li>
 *   <li>{@link #ACTION_RESOLUTION_AUTHORIZED}: Resolution authorized for durable lock or retry.</li>
 *   <li>{@link #ACTION_RESOLVED}: Resolution applied and lock consumed/cleared.</li>
 * </ul>
 */
public enum ToolAttemptAuditEventKind {
    ATTEMPT_ADMITTED,
    LAUNCH_REQUESTED,
    ATTEMPT_SETTLED,
    ACTION_LOCKED,
    ACTION_RESOLUTION_AUTHORIZED,
    ACTION_RESOLVED;

    /**
     * Returns true if this event is bound to a resolution identity rather than an attempt identity.
     */
    public boolean isResolutionEvent() {
        return this == ACTION_RESOLUTION_AUTHORIZED || this == ACTION_RESOLVED;
    }

    /**
     * Returns true if this event is bound to an attempt identity.
     */
    public boolean isAttemptEvent() {
        return !isResolutionEvent();
    }
}
