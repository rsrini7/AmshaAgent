package com.arcclaw.core.state;

/**
 * Lifecycle status of an agent checkpoint.
 */
public enum CheckpointStatus {
    /** Session created, agent not yet executed. */
    NOT_STARTED,
    /** Agent loop is actively executing. */
    IN_PROGRESS,
    /** Agent loop is paused, waiting for human approval to execute a tool. */
    AWAITING_APPROVAL,
    /** Agent loop completed successfully (FINISH action reached). */
    COMPLETED,
    /** Agent loop failed due to error or exceeded iterations. */
    FAILED,
    /** Agent loop was halted due to a compliance or security violation (e.g. mandatory audit write failure). */
    COMPROMISED
}
