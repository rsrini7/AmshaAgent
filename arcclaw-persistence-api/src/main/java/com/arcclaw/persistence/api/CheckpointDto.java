package com.arcclaw.persistence.api;

import java.time.Instant;

/**
 * Transfer object for checkpoint data.
 * This DTO avoids circular dependencies by not depending on arcclaw-core domain types.
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
    String errorCode,
    String traceId,
    String rootCauseClass,
    Instant createdAt,
    Instant updatedAt
) {}