package com.arcclaw.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port interface for checkpoint persistence operations.
 * This interface isolates persistence concerns from the core domain.
 * 
 * All operations are handled through this adapter interface. Implementations
 * (e.g., SqliteCheckpointStoreAdapter) encapsulate retry logic, transaction
 * boundaries, and backend-specific behavior.
 * 
 * Uses CheckpointDto (a simple DTO) instead of JPA entities to avoid circular
 * dependencies. Adapters translate between CheckpointDto and their internal
 * domain models.
 */
public interface CheckpointStorePort {

    /**
     * Saves or updates a checkpoint.
     * 
     * @param checkpoint the checkpoint DTO to save
     * @return the saved checkpoint DTO with ID populated
     */
    CheckpointDto save(CheckpointDto checkpoint);

    /**
     * Saves or updates a checkpoint and its granular reasoning steps atomically.
     * 
     * @param checkpoint the checkpoint DTO to save
     * @param steps the list of reasoning steps associated with the checkpoint
     * @return the saved checkpoint DTO with ID populated
     */
    CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpoint, List<ReasoningStepDto> steps);

    /**
     * Finds a checkpoint by its ID.
     * 
     * @param id the checkpoint ID
     * @return an Optional containing the checkpoint DTO if found
     */
    Optional<CheckpointDto> findById(Long id);

    /**
     * Finds a unique checkpoint for a session and role.
     */
    Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole);

    /**
     * Finds all active (non-terminal) checkpoints for a session.
     * 
     * @param sessionId the session ID
     * @return list of active checkpoint DTOs for the session
     */
    List<CheckpointDto> findActiveBySession(String sessionId);

    /**
     * Transitions a checkpoint to a new status.
     * 
     * @param id the checkpoint ID
     * @param newStatus the new status (as string representation of CheckpointStatus)
     */
    void transitionStatus(Long id, String newStatus);

    /**
     * Atomically transitions a checkpoint from one status to another.
     * Used for distributed locking and race prevention (§T5-6).
     *
     * @param id the checkpoint ID
     * @param expectedStatus the current status that must match
     * @param newStatus the target status
     * @return true if the transition was successful
     */
    boolean atomicTransition(Long id, String expectedStatus, String newStatus);

    /**
     * Atomically transitions the latest checkpoint for a session/role to RESUMING
     * if it is currently AWAITING_APPROVAL (§T5-4).
     *
     * @return true if successfully claimed for resume
     */
    boolean claimForResume(String sessionId, String agentRole);

    /**
     * Finds checkpoints that are stale and awaiting approval.
     * 
     * @param cutoff the cutoff time - checkpoints updated before this time are considered stale
     * @return list of stale approval checkpoint DTOs
     */
    List<CheckpointDto> findStaleApprovals(Instant cutoff);

    /**
     * Finds the latest checkpoint awaiting approval for a given session prefix (e.g. cron).
     */
    Optional<CheckpointDto> findLatestAwaitingApprovalWithPrefix(String sessionPrefix);
    /**
     * Returns the absolute latest checkpoint for this session, regardless of status.
     * Used for continuity and continuity phrases (§7.29).
     */
    Optional<CheckpointDto> findLatestBySession(String sessionId);
    Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId);
    Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status);

    /**
     * Replaces all granular steps for a checkpoint with the new list.
     * Implementation should handle sequence numbers.
     */
    void saveSteps(Long checkpointId, List<ReasoningStepDto> steps);

    /**
     * Loads all granular steps for a checkpoint, ordered by sequence number.
     */
    List<ReasoningStepDto> loadSteps(Long checkpointId);

    void deleteSteps(Long checkpointId);

    List<CheckpointDto> findByStatus(String status);
    boolean isCheckpointExpired(String sessionId, long timeoutHours);
    boolean isSessionActive(String sessionId);

    /**
     * Finds the absolute latest checkpoint awaiting approval for the session.
     */
    Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId);

    /**
     * Finds IN_PROGRESS checkpoints that haven't been updated since cutoff (JVM crashes).
     */
    List<CheckpointDto> findInProgressZombies(Instant cutoff);

    /**
     * Checks if there are any pending tool approvals for a session.
     * 
     * @param sessionId the session ID
     * @return true if the session has pending approvals
     */
    boolean hasPendingApproval(String sessionId);

    /**
     * Marks all non-terminal checkpoints for a session as FAILED.
     * Used during session termination/cleanup.
     * Architecture rule (§5.18): Excludes all terminal statuses.
     * 
     * @param sessionId the session ID
     */
    void markCheckpointsFailed(String sessionId);

    /**
     * Deletes a checkpoint with matching (sessionId, agentRole).
     * 
     * @param sessionId the session ID
     * @param role the agent role
     */
    void deleteCheckpoint(String sessionId, String role);

    /**
     * Atomically finds or creates a checkpoint and updates its metadata and reasoning steps.
     * Restores TOCTOU safety (§FIX 1).
     */
    CheckpointUpsertResult upsertWithSteps(String sessionId, String agentRole, 
        String stateJson, String autonomy, int iterationsUsed, String originalGoal);

    /**
     * Atomically upserts a checkpoint and finalizes its status to prevent TOCTOU races
     * with background schedulers.
     */
    CheckpointUpsertResult upsertAndFinalize(String sessionId, String agentRole, 
        String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal);

    List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason);

    void purgeOldChatHistory(Instant cutoff);

    void markFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass);
    void markCompleted(Long checkpointId);
    void markOrphaned(Long checkpointId, String reason);
    void archive(Long checkpointId);

    /**
     * Atomically claims a checkpoint for retry by transitioning its status
     * from AWAITING_RETRY to IN_PROGRESS and clearing retry metadata.
     * @return true if successfully claimed
     */
    boolean claimForRetry(Long id);

    boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision);
}
