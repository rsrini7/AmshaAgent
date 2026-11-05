package com.amsha.persistence.api;

import java.util.List;

/**
 * Port interface for atomic, idempotent checkpoint reasoning step and attempt link appends
 * per Proposal §12.6.
 */
public interface CheckpointAppendPort {

    /**
     * Atomically appends a reasoning step and links supplied attempt IDs to that step under
     * a compare-and-set checkpoint version check in workspace scope.
     *
     * <p><b>Replay Precedence &amp; Idempotency:</b>
     * If all supplied attempt IDs are already linked to the specified checkpoint and the stored step
     * matches {@code step} across all persisted fields, this method immediately returns
     * {@link CheckpointAppendResult#ALREADY_PRESENT} without checking {@code expectedVersion} or mutating
     * checkpoint history. Replay precedence ensures that retried appends for already-settled attempts succeed
     * idempotently even if the checkpoint version was subsequently advanced by concurrent operations.
     *
     * <p><b>Preconditions and Validation:</b>
     * <ul>
     *   <li>{@code workspaceId} must be non-null and non-blank; otherwise returns {@link CheckpointAppendResult#CONFLICT}.</li>
     *   <li>{@code attemptIds} must be non-empty and duplicate-free; malformed/empty IDs return {@link CheckpointAppendResult#CONFLICT}.</li>
     *   <li>All attempt IDs must exist in {@code workspaceId} and match the checkpoint's session.</li>
     *   <li>If attempt IDs are split across steps or checkpoints, or stored step does not match, returns {@link CheckpointAppendResult#CONFLICT}.</li>
     *   <li>If attempt IDs are not yet linked and {@code expectedVersion} does not match current checkpoint version,
     *       returns {@link CheckpointAppendResult#STALE} with guaranteed rollback of preliminary writes.</li>
     *   <li>On success, writes the step, inserts attempt links, increments checkpoint version, and returns {@link CheckpointAppendResult#APPENDED}.</li>
     * </ul>
     *
     * @param workspaceId non-null, non-blank workspace identifier
     * @param checkpointId the checkpoint ID
     * @param expectedVersion the expected version of the checkpoint
     * @param step the reasoning step to append
     * @param attemptIds non-empty list of unique attempt IDs to link
     * @return the outcome status
     */
    CheckpointAppendResult appendResultIfAbsent(
            String workspaceId, Long checkpointId, int expectedVersion, ReasoningStepDto step, List<String> attemptIds);
}
