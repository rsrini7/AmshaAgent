package com.arcclaw.core.state;

/**
 * Lifecycle status of an agent checkpoint.
 *
 * <p>Terminal states (no further transitions possible):
 * {@link #COMPLETED}, {@link #FAILED}, {@link #LOOP_EXCEEDED},
 * {@link #DENIED}, {@link #COMPROMISED}.
 *
 * <p>Active states:
 * {@link #NOT_STARTED}, {@link #IN_PROGRESS}, {@link #AWAITING_APPROVAL}.
 */
public enum CheckpointStatus {

    /** Session created; agent loop has not yet started execution. */
    NOT_STARTED,

    /** Agent loop is actively executing iterations. */
    IN_PROGRESS,

    /**
     * Agent loop is paused, waiting for human approval before executing
     * a tool call. Transitions to {@link #IN_PROGRESS} on approval,
     * or {@link #DENIED} if the user explicitly rejects the tool call.
     */
    AWAITING_APPROVAL,

    /**
     * The user explicitly denied a HITL tool-call approval request.
     * <p>
     * Distinct from {@link #FAILED} — the agent did not crash; the user
     * made an intentional decision to reject the pending tool invocation.
     * Terminal. No retry without a new session.
     */
    DENIED,

    /**
     * Agent loop completed successfully — the FINISH action was reached
     * and an answer was returned to the user.
     * Terminal.
     */
    COMPLETED,

    /**
     * Agent loop terminated due to an unrecoverable error: a tool threw
     * an unexpected exception, schema self-correction was exhausted, or
     * a consecutive-failure circuit breaker tripped.
     * <p>
     * Does NOT include loop iteration cap exhaustion — see {@link #LOOP_EXCEEDED}.
     * Terminal.
     */
    FAILED,

    /**
     * Agent loop terminated because the maximum iteration count was reached
     * before a FINISH action was produced ({@code AgentResult.loopExceeded()}).
     * <p>
     * Distinct from {@link #FAILED} — the agent did not crash; it simply
     * could not complete the goal within its allocated budget. Useful for
     * tuning {@code maxIterations} and identifying goals that are too broad.
     * Terminal.
     */
    LOOP_EXCEEDED,

    /**
     * Agent loop was halted due to a security or compliance violation.
     * Examples: mandatory audit write failure, PII policy breach detected
     * by a safety reflex, or a governance rule that cannot be satisfied.
     * <p>
     * This status indicates the session state may be incomplete or
     * partially corrupted and MUST be flagged for human review.
     * Terminal.
     */
    COMPROMISED
}