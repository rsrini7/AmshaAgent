package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Transfer object for agent cron jobs.
 * This DTO avoids circular dependencies by not depending on amsha-core domain types.
 */
public record AgentCronDto(
        String id,
        String sessionId,
        String userId,
        String chatId,
        String channelType,
        String channelPlatformId,
        String cronExpression,
        String timezone,
        boolean runOnce,
        String goal,
        Instant nextFireTime,
        Instant lastFireTime,
        String status,
        String executionRole,
        String errorReason,
        String lastDeliveryId,
        Instant createdAt,
        Instant updatedAt) {}
