package com.arcclaw.core.state;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.arcclaw.channel.api.hitl.PendingApprovalChecker;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.security.AutonomyLevel;

/**
 * Contract for persisting and retrieving agent reasoning checkpoints.
 * <p>
 * Implementations handle Jackson serialization of {@code List<ReasoningStep>}
 * to/from the {@link AgentCheckpoint#stateJson} field.
 */
public interface StatefulAgentStore extends PendingApprovalChecker {

    AgentCheckpoint save(String sessionId, String agentRole, String userId, AutonomyLevel autonomy, List<ReasoningStep> steps, String goal);

    default AgentCheckpoint save(String sessionId, String agentRole, List<ReasoningStep> steps, String goal) {
        return save(sessionId, agentRole, "unknown", AutonomyLevel.FULL, steps, goal);
    }

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
    Map<String, Object> deserializeContextData(String json);

    /**
     * Persists granular reasoning steps for a specific checkpoint.
     */
    void saveSteps(Long checkpointId, List<ReasoningStep> steps);

    /**
     * Saves a checkpoint and its granular reasoning steps atomically.
     */
    AgentCheckpoint saveWithSteps(AgentCheckpoint checkpoint, List<ReasoningStep> steps);

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
     * Mark a checkpoint as failed with full diagnostic context.
     */
    void markFailed(Long checkpointId, String reason, String errorCode, String traceId, String rootCauseClass);

    /**
     * Mark a checkpoint as failed with full diagnostic context.
     */
    void markFailed(AgentCheckpoint checkpoint, String reason, String errorCode, String traceId, String rootCauseClass);

    /**
     * Mark a checkpoint as ORPHANED due to scheduler abandonement or process crash.
     */
    void markOrphaned(Long checkpointId, String reason);

    /**
     * Mark a checkpoint as completed.
     *
     * @param checkpointId The checkpoint entity ID.
     */
    void markCompleted(Long checkpointId);
    void archive(Long checkpointId);

    /**
     * Updates only the channel_type and channel_platform_id on a checkpoint
     * via native SQL, without touching any other field (especially status).
     * Used by tryStampChannelInfo to avoid overwriting AWAITING_APPROVAL.
     */
    void stampChannelInfo(Long checkpointId, String channelType, String channelPlatformId);

    /**
     * Save the final state of a checkpoint and mark it as completed or failed.
     * <p>
     * This creates/updates the {@link AgentCheckpoint} and persists all
     * {@link ReasoningStepEntity} rows in a single atomic transaction.
     *
     * @param sessionId   The workspace session ID.
     * @param agentRole   The agent role identifier.
     * @param userId      The user ID.
     * @param autonomy    The autonomy level.
     * @param steps       The final reasoning history to persist.
     * @param finalStatus The terminal status to set.
     * @param goal        The original user goal for continuity tracking.
     * @return The finalized checkpoint.
     */
    AgentCheckpoint saveAndFinalize(String sessionId, String agentRole, String userId, AutonomyLevel autonomy,
                                    List<ReasoningStep> steps,
                                    CheckpointStatus finalStatus,
                                    String goal);

    default AgentCheckpoint saveAndFinalize(String sessionId, String agentRole, List<ReasoningStep> steps,
                                            CheckpointStatus finalStatus, String goal) {
        return saveAndFinalize(sessionId, agentRole, "unknown", AutonomyLevel.FULL, steps, finalStatus, goal);
    }

    /**
     * Load reasoning steps for a specific checkpoint from the granular
     * step repository.
     *
     * @param checkpointId The checkpoint ID.
     * @return List of reasoning steps reconstructed from entities.
     */
    List<ReasoningStep> loadSteps(Long checkpointId);

    /**
     * Load the most recent autonomy level for a session from its checkpoints.
     */
    Optional<AutonomyLevel> loadLatestAutonomyForSession(String sessionId);

    /**
     * Atomically sets one tool's decision in the checkpoint's
     * batchToolDecisions JSON map. §7.20
     *
     * @param sessionId   the active session
     * @param agentRole   the agent role
     * @param toolName    the tool whose decision to update
     * @param decision    APPROVED or DENIED (§7.20)
     * @return true if the checkpoint was found and updated; false otherwise
     */
    boolean updateBatchToolDecision(
        String sessionId, String agentRole,
        String toolName, String decision);

    /**
     * Returns true if all tools in the batchToolDecisions map have
     * a non-PENDING decision. §7.22
     */
    boolean isAllBatchToolsDecided(String sessionId, String agentRole);

    void deleteCheckpoint(String sessionId, String role);

    /**
     * Returns all AWAITING_APPROVAL checkpoints whose updatedAt is
     * older than the given cutoff. Used by stale-checkpoint GC. §7.26
     */
    List<AgentCheckpoint> findStaleAwaitingApproval(Instant cutoff);

    /**
     * Returns all checkpoints with the specified status.
     * Used by the rate-limit retry scheduler.
     */
    List<AgentCheckpoint> findByStatus(CheckpointStatus status);

    /**
     * Atomically marks the session as RESUMING.
     * @return true if this caller is the first to mark it (safe to resume)
     *         false if another thread already marked it (skip - already resuming)
     * §7.29, §T5-4
     */
    boolean claimForResume(String sessionId, String agentRole);

    /**
     * Overloaded to accept a browser-tool flag for adjusting the resumption TTL.
     */
    boolean claimForResume(String sessionId, String agentRole, boolean hasBrowserTool);

    /**
     * Atomically marks the session as RESUMING (generic execution lock).
     * @return true if successfully claimed
     */
    boolean markResuming(String sessionId);

    /**
     * Atomically claims a checkpoint for retry by transitioning its status
     * from AWAITING_RETRY to IN_PROGRESS and clearing retry metadata.
     * @return true if successfully claimed
     */
    boolean claimForRetry(Long id);
 
    /**
     * Returns true if the session is currently marked as RESUMING in-memory.
     * §7.29
     */
    boolean isBatchResuming(String sessionId);

    /**
     * Clears the RESUMING flag for the session.
     */
    void clearBatchResuming(String sessionId);

    /**
     * Clears the RESUMING flag for the session (generic execution lock).
     */
    void clearResuming(String sessionId);

    void releaseResumeClaim(String sessionId, String agentRole);

    /**
     * Returns the most-recent checkpoint for this session that is in
     * {@link CheckpointStatus#AWAITING_APPROVAL} status, regardless of agentRole.
     * Used by ApproveReflex to avoid stale-row ambiguity when multiple rows
     * exist for the same (sessionId, agentRole) pair.
     */
    Optional<AgentCheckpoint> findLatestAwaitingApproval(String sessionId);

    /**
     * Finds the latest checkpoint awaiting approval for a session ID containing the given string (e.g. cronId).
     */
    Optional<AgentCheckpoint> findLatestAwaitingApprovalBySessionContaining(String segment);

    /**
     * Retrieves the absolute latest checkpoint for this session, regardless of status or role.
     */
    Optional<AgentCheckpoint> findLatestCheckpoint(String sessionId);

    /**
     * Retrieves the latest non-archived checkpoint for this session.
     */
    Optional<AgentCheckpoint> findLatestNonArchivedCheckpoint(String sessionId);

    /**
     * Returns the agentRole of the most recent non-archived checkpoint for this session.
     * Used for continuity phrases (e.g. "try again") to preserve current agent role.
     */
    Optional<String> findLatestRole(String sessionId);

    /**
     * Returns the latest checkpoint for this session with the specified status.
     */
    Optional<AgentCheckpoint> findLatestByStatus(String sessionId, CheckpointStatus status);

    /**
     * Returns {@code true} if the latest AWAITING_APPROVAL checkpoint for this
     * session has been waiting longer than {@code timeoutHours}, or if no such
     * checkpoint exists (treat as expired - nothing to approve).
     *
     * @param sessionId    the workspace session to check
     * @param timeoutHours the HITL approval window in hours
     * @return true if expired or missing; false if still within the window
     */
    boolean isCheckpointExpired(String sessionId, long timeoutHours);

    /**
     * Returns {@code true} if the session is currently active (has at least one
     * non-terminal checkpoint). Terminal statuses: COMPLETED, FAILED,
     * LOOP_EXCEEDED, DENIED, COMPROMISED, ARCHIVED.
     *
     * @param sessionId the workspace session to check
     * @return true if active; false if completed or missing
     */
    boolean isSessionActive(String sessionId);

    boolean hasPendingApproval(String sessionId);

    boolean hasPendingApprovalForChat(String chatId);
}
