package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Summary record for an execution run across its trace events.
 * §1.2: Core-persistence boundary.
 */
public record ExecutionRunSummaryDto(
        String runId,
        String sessionId,
        int turnNumber,
        String paradigm,
        long eventCount,
        long maxSequence,
        String terminalStatus,
        Instant startedAt,
        Instant completedAt) {

    public ExecutionRunSummaryDto {
        Objects.requireNonNull(runId, "runId is required");
        Objects.requireNonNull(sessionId, "sessionId is required");
    }
}
