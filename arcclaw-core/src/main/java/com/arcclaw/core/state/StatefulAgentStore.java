package com.arcclaw.core.state;

import java.util.List;
import java.util.Optional;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.security.AutonomyLevel;

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
     * Update an existing checkpoint directly.
     *
     * @param checkpoint The checkpoint to save.
     * @return The updated checkpoint.
     */
    AgentCheckpoint save(AgentCheckpoint checkpoint);

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
     * Mark a checkpoint as failed.
     * 
     * @param checkpoint The checkpoint to mark as failed.
     * @param reason     Human-readable failure reason.
     */
    void markFailed(AgentCheckpoint checkpoint, String reason);

    /**
     * Mark a checkpoint as completed.
     *
     * @param checkpointId The checkpoint entity ID.
     */
    void markCompleted(Long checkpointId);

    /**
     * Save the final state of a checkpoint and mark it as completed or failed.
     * <p>
     * This creates/updates the {@link AgentCheckpoint} and persists all
     * {@link ReasoningStepEntity} rows in a single atomic transaction.
     *
     * @param sessionId   The workspace session ID.
     * @param agentRole   The agent role identifier.
     * @param steps       The final reasoning history to persist.
     * @param finalStatus The final status (COMPLETED or FAILED).
     * @return The finalized checkpoint.
     */
    AgentCheckpoint saveAndFinalize(String sessionId, String agentRole,
                                    List<ReasoningStep> steps,
                                    CheckpointStatus finalStatus);

    /**
     * Load reasoning steps for a specific checkpoint from the granular
     * step repository.
     *
     * @param checkpointId The checkpoint ID.
     * @return List of reasoning steps reconstructed from entities.
     */
    List<ReasoningStep> loadSteps(Long checkpointId);

    /**
     * Find the most recent AWAITING_APPROVAL checkpoint for a cron session
     * with the given cronId. Matches sessionId pattern: "cron-{cronId}-%".
     */
    Optional<AgentCheckpoint> findLatestAwaitingApprovalForCron(String cronId);

    /**
     * Load the most recent autonomy level for a session from its checkpoints.
     */
    Optional<AutonomyLevel> loadLatestAutonomyForSession(String sessionId);
}
