package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Transfer object for checkpoint data.
 * This DTO avoids circular dependencies by not depending on amsha-core domain types.
 */
public record CheckpointDto(
        Long id,
        String sessionId,
        String agentRole,
        String status,
        String autonomyLevel,
        String stateJson,
        String channelPlatformId,
        String channelType,
        String originalGoal,
        Instant failedAt,
        Instant completedAt,
        String failReason,
        String pendingToolName,
        String batchToolDecisions,
        String pendingToolParametersJson,
        String preExecutedResults,
        Integer maxIterations,
        int iterationsUsed,
        Integer retryAfterMs,
        String systemNote,
        String contextDataJson,
        String errorCode,
        String traceId,
        String rootCauseClass,
        Long parentCheckpointId,
        int depth,
        Instant createdAt,
        Instant updatedAt) {
    public CheckpointDto withBatchToolDecisions(String batchToolDecisions) {
        return new CheckpointDto(
                id,
                sessionId,
                agentRole,
                status,
                autonomyLevel,
                stateJson,
                channelPlatformId,
                channelType,
                originalGoal,
                failedAt,
                completedAt,
                failReason,
                pendingToolName,
                batchToolDecisions,
                pendingToolParametersJson,
                preExecutedResults,
                maxIterations,
                iterationsUsed,
                retryAfterMs,
                systemNote,
                contextDataJson,
                errorCode,
                traceId,
                rootCauseClass,
                parentCheckpointId,
                depth,
                createdAt,
                updatedAt);
    }

    @SuppressWarnings("ReferenceEquality") // intentional identity check for short-circuit optimization
    public CheckpointDto ensureTimestamps(Instant fallbackCreated, Instant fallbackUpdated) {
        Instant c = createdAt != null ? createdAt : fallbackCreated;
        Instant u = updatedAt != null ? updatedAt : fallbackUpdated;
        if (c == createdAt && u == updatedAt) return this;
        return new CheckpointDto(
                id,
                sessionId,
                agentRole,
                status,
                autonomyLevel,
                stateJson,
                channelPlatformId,
                channelType,
                originalGoal,
                failedAt,
                completedAt,
                failReason,
                pendingToolName,
                batchToolDecisions,
                pendingToolParametersJson,
                preExecutedResults,
                maxIterations,
                iterationsUsed,
                retryAfterMs,
                systemNote,
                contextDataJson,
                errorCode,
                traceId,
                rootCauseClass,
                parentCheckpointId,
                depth,
                c,
                u);
    }
}
