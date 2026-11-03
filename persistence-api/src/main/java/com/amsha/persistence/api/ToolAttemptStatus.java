package com.amsha.persistence.api;

import java.util.Set;

/**
 * Lifecycle status of a tool attempt per Proposal §12.3 and §12.4.
 *
 * <ul>
 *   <li>{@link #ADMITTED}: Attempt created with allocated identity and committed outbox event.</li>
 *   <li>{@link #LAUNCH_REQUESTED}: Launch intent committed and outbox event acknowledged.</li>
 *   <li>{@link #RUNNING}: Executor start confirmed.</li>
 *   <li>{@link #SETTLED_SUCCESS}: Tool returned confirmed success observation.</li>
 *   <li>{@link #SETTLED_ERROR}: Tool returned confirmed error result.</li>
 *   <li>{@link #TIMED_OUT}: Deadline expired with confirmed termination.</li>
 *   <li>{@link #CANCELLED_BEFORE_LAUNCH}: Cancelled prior to launch intent.</li>
 *   <li>{@link #INDETERMINATE}: Non-definitive execution outcome.</li>
 * </ul>
 */
public enum ToolAttemptStatus {
    ADMITTED,
    LAUNCH_REQUESTED,
    RUNNING,
    SETTLED_SUCCESS,
    SETTLED_ERROR,
    TIMED_OUT,
    CANCELLED_BEFORE_LAUNCH,
    INDETERMINATE;

    private static final Set<ToolAttemptStatus> TERMINAL_STATUSES =
            Set.of(SETTLED_SUCCESS, SETTLED_ERROR, TIMED_OUT, CANCELLED_BEFORE_LAUNCH, INDETERMINATE);

    /**
     * Returns true if this status represents an immutable terminal state.
     */
    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(this);
    }

    /**
     * Returns true if this status represents a settled outcome (success or error).
     */
    public boolean isSettled() {
        return this == SETTLED_SUCCESS || this == SETTLED_ERROR;
    }

    /**
     * Returns true when a consumed AT_MOST_ONCE claim may replay this result.
     */
    public boolean isReplayableAtMostOnceOutcome() {
        return this == SETTLED_SUCCESS || this == SETTLED_ERROR || this == TIMED_OUT;
    }

    /**
     * Evaluates if a transition from this status to targetStatus is legally permitted per §12.3.
     *
     * @param targetStatus the desired destination status
     * @return true if the transition is allowed
     */
    public boolean canTransitionTo(ToolAttemptStatus targetStatus) {
        if (targetStatus == null) {
            return false;
        }
        return switch (this) {
            case ADMITTED -> targetStatus == LAUNCH_REQUESTED || targetStatus == CANCELLED_BEFORE_LAUNCH;
            case LAUNCH_REQUESTED ->
                targetStatus == RUNNING
                        || targetStatus == CANCELLED_BEFORE_LAUNCH
                        || targetStatus == SETTLED_SUCCESS
                        || targetStatus == SETTLED_ERROR
                        || targetStatus == TIMED_OUT
                        || targetStatus == INDETERMINATE;
            case RUNNING ->
                targetStatus == SETTLED_SUCCESS
                        || targetStatus == SETTLED_ERROR
                        || targetStatus == TIMED_OUT
                        || targetStatus == INDETERMINATE;
            case SETTLED_SUCCESS, SETTLED_ERROR, TIMED_OUT, CANCELLED_BEFORE_LAUNCH, INDETERMINATE -> false;
        };
    }

    /**
     * Evaluates whether a prior attempt with this status is eligible for an intentional retry
     * under the given idempotency mode and whether an authorized resolution is being consumed
     * per Proposal §3 and §12.4.
     *
     * <ul>
     *   <li>{@link #SETTLED_SUCCESS} is NEVER eligible for retry under any mode; success returns
     *       the stored observation and must never re-execute.</li>
     *   <li>{@link ToolAttemptIdempotencyMode#AT_MOST_ONCE}: Only {@link #CANCELLED_BEFORE_LAUNCH}
     *       permits a new attempt (where no launch intent was committed and the claim was released).
     *       Attempts in {@link #SETTLED_ERROR}, {@link #TIMED_OUT}, or {@link #INDETERMINATE}
     *       cannot be retried; approval cannot override this fence.</li>
     *   <li>{@link ToolAttemptIdempotencyMode#IDEMPOTENT}: {@link #SETTLED_ERROR}, {@link #TIMED_OUT},
     *       and {@link #CANCELLED_BEFORE_LAUNCH} permit automatic/loop retry. {@link #INDETERMINATE}
     *       is explicitly not safe to auto-replay and requires an authorized resolution.</li>
     *   <li>{@link ToolAttemptIdempotencyMode#NON_IDEMPOTENT}: {@link #CANCELLED_BEFORE_LAUNCH}
     *       permits admission under normal governance. {@link #SETTLED_ERROR} and {@link #TIMED_OUT}
     *       require an authorized one-shot retry approval. {@link #INDETERMINATE} requires the
     *       separate evidence path (verified postcondition evidence or operator acknowledgement).</li>
     * </ul>
     *
     * @param mode the idempotency mode of the tool
     * @param hasApprovedResolution true if an authorized resolution is being consumed
     * @return true if retry admission is legally permitted
     */
    public boolean isEligibleForRetry(ToolAttemptIdempotencyMode mode, boolean hasApprovedResolution) {
        if (mode == null || !isTerminal() || this == SETTLED_SUCCESS) {
            return false;
        }
        return switch (mode) {
            case AT_MOST_ONCE -> this == CANCELLED_BEFORE_LAUNCH;
            case IDEMPOTENT ->
                switch (this) {
                    case SETTLED_ERROR, TIMED_OUT, CANCELLED_BEFORE_LAUNCH -> true;
                    case INDETERMINATE -> hasApprovedResolution;
                    default -> false;
                };
            case NON_IDEMPOTENT ->
                switch (this) {
                    case CANCELLED_BEFORE_LAUNCH -> true;
                    case SETTLED_ERROR, TIMED_OUT, INDETERMINATE -> hasApprovedResolution;
                    default -> false;
                };
        };
    }
}
