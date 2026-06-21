package com.arcclaw.persistence.api;

import java.time.Instant;

/**
 * Helper for creating modified copies of {@link CheckpointDto} without
 * repeating all 27 constructor arguments at every call site.
 */
public final class CheckpointDtoHelper {

    private CheckpointDtoHelper() {}

    /** Copy with new status and updatedAt=now. */
    public static CheckpointDto withStatus(CheckpointDto cp, String newStatus) {
        return new CheckpointDto(
                cp.id(),
                cp.sessionId(),
                cp.agentRole(),
                newStatus,
                cp.autonomyLevel(),
                cp.stateJson(),
                cp.channelPlatformId(),
                cp.channelType(),
                cp.originalGoal(),
                cp.failedAt(),
                cp.completedAt(),
                cp.failReason(),
                cp.pendingToolName(),
                cp.batchToolDecisions(),
                cp.pendingToolParametersJson(),
                cp.preExecutedResults(),
                cp.maxIterations(),
                cp.iterationsUsed(),
                cp.retryAfterMs(),
                cp.systemNote(),
                cp.contextDataJson(),
                cp.errorCode(),
                cp.traceId(),
                cp.rootCauseClass(),
                cp.parentCheckpointId(),
                cp.depth(),
                cp.createdAt(),
                Instant.now());
    }

    /** Copy with new status, failedAt, and failReason. */
    public static CheckpointDto withFailure(
            CheckpointDto cp,
            String status,
            Instant failedAt,
            String failReason,
            String errorCode,
            String traceId,
            String rootCauseClass) {
        return new CheckpointDto(
                cp.id(),
                cp.sessionId(),
                cp.agentRole(),
                status,
                cp.autonomyLevel(),
                cp.stateJson(),
                cp.channelPlatformId(),
                cp.channelType(),
                cp.originalGoal(),
                failedAt,
                cp.completedAt(),
                failReason,
                cp.pendingToolName(),
                cp.batchToolDecisions(),
                cp.pendingToolParametersJson(),
                cp.preExecutedResults(),
                cp.maxIterations(),
                cp.iterationsUsed(),
                cp.retryAfterMs(),
                cp.systemNote(),
                cp.contextDataJson(),
                errorCode,
                traceId,
                rootCauseClass,
                cp.parentCheckpointId(),
                cp.depth(),
                cp.createdAt(),
                Instant.now());
    }

    /** Copy with new status and completedAt. */
    public static CheckpointDto withCompletion(CheckpointDto cp, String status, Instant completedAt) {
        return new CheckpointDto(
                cp.id(),
                cp.sessionId(),
                cp.agentRole(),
                status,
                cp.autonomyLevel(),
                cp.stateJson(),
                cp.channelPlatformId(),
                cp.channelType(),
                cp.originalGoal(),
                cp.failedAt(),
                completedAt,
                cp.failReason(),
                cp.pendingToolName(),
                cp.batchToolDecisions(),
                cp.pendingToolParametersJson(),
                cp.preExecutedResults(),
                cp.maxIterations(),
                cp.iterationsUsed(),
                cp.retryAfterMs(),
                cp.systemNote(),
                cp.contextDataJson(),
                cp.errorCode(),
                cp.traceId(),
                cp.rootCauseClass(),
                cp.parentCheckpointId(),
                cp.depth(),
                cp.createdAt(),
                Instant.now());
    }

    /** Copy with new batchToolDecisions. */
    public static CheckpointDto withBatchDecisions(CheckpointDto cp, String updatedDecisions) {
        return new CheckpointDto(
                cp.id(),
                cp.sessionId(),
                cp.agentRole(),
                cp.status(),
                cp.autonomyLevel(),
                cp.stateJson(),
                cp.channelPlatformId(),
                cp.channelType(),
                cp.originalGoal(),
                cp.failedAt(),
                cp.completedAt(),
                cp.failReason(),
                cp.pendingToolName(),
                updatedDecisions,
                cp.pendingToolParametersJson(),
                cp.preExecutedResults(),
                cp.maxIterations(),
                cp.iterationsUsed(),
                cp.retryAfterMs(),
                cp.systemNote(),
                cp.contextDataJson(),
                cp.errorCode(),
                cp.traceId(),
                cp.rootCauseClass(),
                cp.parentCheckpointId(),
                cp.depth(),
                cp.createdAt(),
                Instant.now());
    }

    /** Copy with new channel info. */
    public static CheckpointDto withChannelInfo(CheckpointDto cp, String channelType, String channelPlatformId) {
        return new CheckpointDto(
                cp.id(),
                cp.sessionId(),
                cp.agentRole(),
                cp.status(),
                cp.autonomyLevel(),
                cp.stateJson(),
                channelPlatformId,
                channelType,
                cp.originalGoal(),
                cp.failedAt(),
                cp.completedAt(),
                cp.failReason(),
                cp.pendingToolName(),
                cp.batchToolDecisions(),
                cp.pendingToolParametersJson(),
                cp.preExecutedResults(),
                cp.maxIterations(),
                cp.iterationsUsed(),
                cp.retryAfterMs(),
                cp.systemNote(),
                cp.contextDataJson(),
                cp.errorCode(),
                cp.traceId(),
                cp.rootCauseClass(),
                cp.parentCheckpointId(),
                cp.depth(),
                cp.createdAt(),
                Instant.now());
    }

    /** Create a new checkpoint with minimal fields (most null/default). */
    public static CheckpointDto create(
            String sessionId,
            String agentRole,
            String status,
            String autonomy,
            String stateJson,
            String originalGoal,
            int iterationsUsed,
            String contextDataJson) {
        Instant now = Instant.now();
        return new CheckpointDto(
                null,
                sessionId,
                agentRole,
                status,
                autonomy,
                stateJson,
                null,
                null,
                originalGoal,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                iterationsUsed,
                null,
                null,
                contextDataJson,
                null,
                null,
                null,
                null,
                0,
                now,
                now);
    }

    /** Copy with reset fail/error fields (for retry). */
    public static CheckpointDto withRetry(CheckpointDto cp, String status) {
        return new CheckpointDto(
                cp.id(),
                cp.sessionId(),
                cp.agentRole(),
                status,
                cp.autonomyLevel(),
                cp.stateJson(),
                cp.channelPlatformId(),
                cp.channelType(),
                cp.originalGoal(),
                null,
                null,
                null,
                cp.pendingToolName(),
                cp.batchToolDecisions(),
                cp.pendingToolParametersJson(),
                cp.preExecutedResults(),
                cp.maxIterations(),
                cp.iterationsUsed(),
                cp.retryAfterMs(),
                cp.systemNote(),
                cp.contextDataJson(),
                null,
                null,
                null,
                cp.parentCheckpointId(),
                cp.depth(),
                cp.createdAt(),
                Instant.now());
    }

    /** Copy with new Orphan reason. */
    public static CheckpointDto withOrphanReason(CheckpointDto cp, String reason) {
        return new CheckpointDto(
                cp.id(),
                cp.sessionId(),
                cp.agentRole(),
                "ORPHANED",
                cp.autonomyLevel(),
                cp.stateJson(),
                cp.channelPlatformId(),
                cp.channelType(),
                cp.originalGoal(),
                cp.failedAt(),
                cp.completedAt(),
                reason,
                cp.pendingToolName(),
                cp.batchToolDecisions(),
                cp.pendingToolParametersJson(),
                cp.preExecutedResults(),
                cp.maxIterations(),
                cp.iterationsUsed(),
                cp.retryAfterMs(),
                cp.systemNote(),
                cp.contextDataJson(),
                cp.errorCode(),
                cp.traceId(),
                cp.rootCauseClass(),
                cp.parentCheckpointId(),
                cp.depth(),
                cp.createdAt(),
                Instant.now());
    }
}
