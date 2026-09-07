package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable transfer record for durably persisted execution trace events.
 * §1.2: Core-persistence boundary.
 */
public record ExecutionTraceDto(
        String eventId,
        String runId,
        String sessionId,
        int turnNumber,
        long sequence,
        String stage,
        String station,
        String status,
        String riskLevel,
        String detail,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        Integer iteration,
        Integer attempt,
        Instant timestamp,
        String paradigm,
        int version,
        Instant createdAt) {

    public ExecutionTraceDto {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(runId, "runId is required");
        Objects.requireNonNull(sessionId, "sessionId is required");
        Objects.requireNonNull(stage, "stage is required");
        Objects.requireNonNull(timestamp, "timestamp is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (turnNumber < 0) {
            throw new IllegalArgumentException("turnNumber must not be negative");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        if (detail != null && detail.length() > 2048) {
            detail = detail.substring(0, 2048);
        }
    }
}
