package com.amsha.persistence.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable DTO representing a single execution of a cron job.
 */
public record CronRunDto(
        String id,
        String cronId,
        String sessionId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        String errorReason,
        int iterationsUsed,
        int inputTokens,
        int outputTokens,
        BigDecimal usdCost,
        Instant createdAt) {}
