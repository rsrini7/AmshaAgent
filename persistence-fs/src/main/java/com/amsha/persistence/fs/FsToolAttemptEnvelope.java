package com.amsha.persistence.fs;

import java.util.List;
import java.util.Objects;

import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptResolutionDto;

/**
 * Versioned filesystem envelope holding attempt state, locks, resolutions, and outbox entries
 * per Proposal §12.4.
 */
public record FsToolAttemptEnvelope(
        int version,
        ToolAttemptDto attempt,
        List<DurableActionLockDto> locks,
        List<ToolAttemptResolutionDto> resolutions,
        List<ToolAttemptAuditOutboxDto> outboxEvents) {

    public FsToolAttemptEnvelope {
        Objects.requireNonNull(attempt, "attempt must not be null");
        locks = locks != null ? List.copyOf(locks) : List.of();
        resolutions = resolutions != null ? List.copyOf(resolutions) : List.of();
        outboxEvents = outboxEvents != null ? List.copyOf(outboxEvents) : List.of();
    }

    public static FsToolAttemptEnvelope of(ToolAttemptDto attempt, ToolAttemptAuditOutboxDto initialOutbox) {
        return new FsToolAttemptEnvelope(
                1, attempt, List.of(), List.of(), initialOutbox != null ? List.of(initialOutbox) : List.of());
    }
}
