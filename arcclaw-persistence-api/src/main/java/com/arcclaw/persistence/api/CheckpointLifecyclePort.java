package com.arcclaw.persistence.api;

import java.time.Instant;
import java.util.List;

public interface CheckpointLifecyclePort {

    void markFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass);

    void markCompleted(Long checkpointId);

    void markOrphaned(Long checkpointId, String reason);

    void archive(Long checkpointId);

    CheckpointUpsertResult upsertWithSteps(String sessionId, String agentRole,
        String stateJson, String autonomy, int iterationsUsed, String originalGoal, String contextDataJson);

    CheckpointUpsertResult upsertAndFinalize(String sessionId, String agentRole,
        String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal, String contextDataJson);

    List<CheckpointDto> findStaleApprovals(Instant cutoff);

    List<CheckpointDto> findInProgressZombies(Instant cutoff);

    List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason);
}
