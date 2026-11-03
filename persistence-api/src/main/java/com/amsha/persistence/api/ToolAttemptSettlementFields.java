package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Settlement and execution fields applied during attempt transition per Proposal §12.4.
 */
public record ToolAttemptSettlementFields(
        Instant eventTimestamp,
        String executionHandle,
        String processStartIdentity,
        Integer exitCode,
        String sanitizedObservation) {

    public ToolAttemptSettlementFields {
        Objects.requireNonNull(eventTimestamp, "eventTimestamp must not be null");
    }

    public static ToolAttemptSettlementFields empty(Instant timestamp) {
        return new ToolAttemptSettlementFields(timestamp, null, null, null, null);
    }

    public static ToolAttemptSettlementFields forLaunch(Instant timestamp) {
        return new ToolAttemptSettlementFields(timestamp, null, null, null, null);
    }

    public static ToolAttemptSettlementFields forRunning(
            Instant timestamp, String executionHandle, String processStartIdentity) {
        return new ToolAttemptSettlementFields(timestamp, executionHandle, processStartIdentity, null, null);
    }

    public static ToolAttemptSettlementFields forSuccess(
            Instant timestamp, String executionHandle, String sanitizedObservation) {
        return new ToolAttemptSettlementFields(timestamp, executionHandle, null, 0, sanitizedObservation);
    }

    public static ToolAttemptSettlementFields forError(
            Instant timestamp, String executionHandle, Integer exitCode, String sanitizedObservation) {
        return new ToolAttemptSettlementFields(timestamp, executionHandle, null, exitCode, sanitizedObservation);
    }
}
