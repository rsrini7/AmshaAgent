package com.amsha.persistence.fs;

import java.beans.ConstructorProperties;

/**
 * Persisted attempt link within an {@link FsCheckpointEnvelope} per Proposal §12.6.
 */
public record FsAttemptLink(
        Long checkpointId, String attemptId, String sessionId, String workspaceId, long reasoningStepId) {

    @ConstructorProperties({"checkpointId", "attemptId", "sessionId", "workspaceId", "reasoningStepId"})
    public FsAttemptLink {}
}
