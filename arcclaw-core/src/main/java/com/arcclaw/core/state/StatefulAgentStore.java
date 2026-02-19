package com.arcclaw.core.state;

import com.arcclaw.core.agent.ReasoningStep;

import java.util.List;
import java.util.Optional;

/**
 * Contract for persisting and retrieving agent reasoning checkpoints.
 * <p>
 * Implementations handle Jackson serialization of {@code List<ReasoningStep>}
 * to/from the {@link AgentCheckpoint#stateJson} field.
 */
public interface StatefulAgentStore {

    /**
     * Save or update a checkpoint with the current reasoning steps.
     *
     * @param sessionId The workspace session ID.
     * @param agentRole The agent role identifier.
     * @param steps     The current reasoning history to serialize.
     * @return The persisted checkpoint.
     */
    AgentCheckpoint save(String sessionId, String agentRole, List<ReasoningStep> steps);

    /**
     * Load the most recent checkpoint for a session and agent role.
     *
     * @param sessionId The workspace session ID.
     * @param agentRole The agent role identifier.
     * @return The checkpoint if found, or empty.
     */
    Optional<AgentCheckpoint> load(String sessionId, String agentRole);

    /**
     * Deserialize the reasoning steps from a checkpoint's stateJson.
     *
     * @param checkpoint The checkpoint to deserialize.
     * @return The list of reasoning steps.
     */
    List<ReasoningStep> deserializeSteps(AgentCheckpoint checkpoint);

    /**
     * Mark a checkpoint as failed with a reason.
     *
     * @param checkpointId The checkpoint entity ID.
     * @param reason       Human-readable failure reason.
     */
    void markFailed(Long checkpointId, String reason);

    /**
     * Mark a checkpoint as completed.
     *
     * @param checkpointId The checkpoint entity ID.
     */
    void markCompleted(Long checkpointId);
}
