package com.arcclaw.core.state;

/**
 * Lifecycle status of an agent checkpoint.
 */
public enum CheckpointStatus {
    /** Agent loop is actively executing. */
    IN_PROGRESS,
    /** Agent loop completed successfully (FINISH action reached). */
    COMPLETED,
    /** Agent loop failed due to error or exceeded iterations. */
    FAILED
}
