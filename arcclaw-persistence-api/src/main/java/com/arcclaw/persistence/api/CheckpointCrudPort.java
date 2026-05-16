package com.arcclaw.persistence.api;

import java.util.List;
import java.util.Optional;

public interface CheckpointCrudPort {

    CheckpointDto save(CheckpointDto checkpoint);

    CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpoint, List<ReasoningStepDto> steps);

    Optional<CheckpointDto> findById(Long id);

    Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole);

    List<CheckpointDto> findByStatus(String status);

    void deleteCheckpoint(String sessionId, String role);

    void saveSteps(Long checkpointId, List<ReasoningStepDto> steps);

    List<ReasoningStepDto> loadSteps(Long checkpointId);

    void deleteSteps(Long checkpointId);

    boolean isCheckpointExpired(String sessionId, long timeoutHours);

    boolean isSessionActive(String sessionId);

    Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId);

    Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status);

    Optional<CheckpointDto> findLatestAwaitingApprovalBySessionContaining(String segment);

    Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId);

    boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision);

    boolean hasPendingApprovalForChat(String chatId);
}
