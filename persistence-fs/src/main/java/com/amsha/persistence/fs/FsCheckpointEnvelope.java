package com.amsha.persistence.fs;

import java.beans.ConstructorProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.ReasoningStepDto;

/**
 * Unified crash-atomic envelope holding checkpoint metadata, version, reasoning steps,
 * and attempt links per Proposal §12.4 and §12.6.
 */
public record FsCheckpointEnvelope(
        int schemaVersion, CheckpointDto checkpoint, List<ReasoningStepDto> steps, List<FsAttemptLink> attemptLinks) {

    public static final int CURRENT_VERSION = 1;

    @ConstructorProperties({"schemaVersion", "checkpoint", "steps", "attemptLinks"})
    public FsCheckpointEnvelope(
            int schemaVersion,
            CheckpointDto checkpoint,
            List<ReasoningStepDto> steps,
            List<FsAttemptLink> attemptLinks) {
        if (schemaVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported checkpoint envelope schema version: " + schemaVersion);
        }
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.id() == null
                || checkpoint.sessionId() == null
                || checkpoint.sessionId().isBlank()
                || checkpoint.agentRole() == null
                || checkpoint.agentRole().isBlank()
                || checkpoint.version() == null
                || checkpoint.version() < 0) {
            throw new IllegalArgumentException("Malformed checkpoint envelope identity or version");
        }
        List<ReasoningStepDto> validatedSteps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        List<FsAttemptLink> validatedLinks = List.copyOf(Objects.requireNonNull(attemptLinks, "attemptLinks"));
        Set<String> linkedAttemptIds = new HashSet<>();
        for (FsAttemptLink link : validatedLinks) {
            if (link.checkpointId() == null
                    || !checkpoint.id().equals(link.checkpointId())
                    || !checkpoint.sessionId().equals(link.sessionId())
                    || link.workspaceId() == null
                    || link.workspaceId().isBlank()
                    || link.attemptId() == null
                    || link.attemptId().isBlank()
                    || link.reasoningStepId() < 1
                    || link.reasoningStepId() > validatedSteps.size()
                    || !linkedAttemptIds.add(link.attemptId())) {
                throw new IllegalArgumentException("Malformed checkpoint attempt link");
            }
        }
        this.schemaVersion = schemaVersion;
        this.checkpoint = checkpoint;
        this.steps = validatedSteps;
        this.attemptLinks = validatedLinks;
    }
}
