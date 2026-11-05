package com.amsha.persistence.api;

/**
 * Result status for {@link CheckpointAppendPort#appendResultIfAbsent} per Proposal §12.6.
 */
public enum CheckpointAppendResult {
    /**
     * Step and attempt links successfully written, and checkpoint version incremented.
     */
    APPENDED,

    /**
     * Every supplied attempt ID is already linked to the same step under this checkpoint,
     * and the stored step matches the supplied step; idempotent replay no-op with no version change.
     */
    ALREADY_PRESENT,

    /**
     * The expected checkpoint version does not match current version; caller must reload and retry.
     */
    STALE,

    /**
     * Unrecoverable conflict: attempt IDs are split across multiple steps, belong to another
     * checkpoint/session/workspace, conflict with another operation, refer to an unknown attempt,
     * contain duplicate or malformed IDs, or the stored step does not match the supplied step.
     */
    CONFLICT
}
