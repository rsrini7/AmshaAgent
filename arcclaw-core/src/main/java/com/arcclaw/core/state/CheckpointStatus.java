package com.arcclaw.core.state;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle status of an agent checkpoint.
 *
 * <p>Terminal states (no further transitions possible):
 * {@link #COMPLETED}, {@link #FAILED}, {@link #LOOP_EXCEEDED},
 * {@link #DENIED}, {@link #COMPROMISED}, {@link #ARCHIVED}.
 *
 * <p>Active states:
 * {@link #NOT_STARTED}, {@link #IN_PROGRESS}, {@link #AWAITING_APPROVAL},
 * {@link #AWAITING_RETRY}.
 *
 * <p>Orphaned states:
 * {@link #ORPHANED}.
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
     * Distinct from {@link #FAILED} - the agent did not crash; the user
     * made an intentional decision to reject the pending tool invocation.
     * Terminal. No retry without a new session.
     */
    DENIED,

    /**
     * Agent loop completed successfully - the FINISH action was reached
     * and an answer was returned to the user.
     * Terminal.
     */
    COMPLETED,

    /**
     * Agent loop terminated due to an unrecoverable error: a tool threw
     * an unexpected exception, schema self-correction was exhausted, or
     * a consecutive-failure circuit breaker tripped.
     * <p>
     * Does NOT include loop iteration cap exhaustion - see {@link #LOOP_EXCEEDED}.
     * Terminal.
     */
    FAILED,

    /**
     * Agent loop terminated because the maximum iteration count was reached
     * before a FINISH action was produced ({@code AgentResult.loopExceeded()}).
     * <p>
     * Distinct from {@link #FAILED} - the agent did not crash; it simply
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
    COMPROMISED,

    /**
     * Agent loop is paused due to a rate limit hit. 
     * Transitions to {@link #IN_PROGRESS} after the retry delay has elapsed.
     */
    AWAITING_RETRY,

    /**
     * Agent loop is in the process of resuming from an {@link #AWAITING_APPROVAL}
     * state. This status acts as a distributed double-resume lock (§T5-4).
     * <p>
     * Once the resume logic successfully enters the agent loop, it transitions
     * to {@link #IN_PROGRESS}.
     */
    RESUMING,

     /**
      * Terminal status set by AgentLoop when a fresh turn starts over an existing
      * COMPLETED/FAILED/COMPROMISED checkpoint. Archived rows are excluded from
      * all load() and findLatestAwaitingApproval() queries so they never shadow
      * a newer AWAITING_APPROVAL row for the same (sessionId, agentRole) pair.
      */
     ARCHIVED,

     /**
      * Terminal status for checkpoints that were abandoned in an active state (like AWAITING_RETRY)
      * for an extended period (e.g., 30+ minutes) due to scheduler outages or process crashes.
      * Flagged for manual intervention or cleanup.
      */
     ORPHANED;

    /**
     * Set of statuses that represent an end-of-life state for a checkpoint.
     * No further transitions are permitted once these are reached.
     * <p>
     * NOTE: RESUMING is included here not because it is end-of-life, but because
     * it acts as a distributed lock that MUST NOT be overwritten by bulk
     * cleanup or termination sweeps (§5.18).
     */
    private static final Set<CheckpointStatus> TERMINAL_STATES = EnumSet.of(
        COMPLETED, FAILED, LOOP_EXCEEDED, DENIED, COMPROMISED, ARCHIVED, ORPHANED, RESUMING
    );

    /**
     * @return true if this status represents an end-of-life state for a checkpoint.
     */
    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    /**
     * @return The immutable set of all terminal statuses.
     */
    public static Set<CheckpointStatus> terminalStatuses() {
        return Collections.unmodifiableSet(TERMINAL_STATES);
    }
}
