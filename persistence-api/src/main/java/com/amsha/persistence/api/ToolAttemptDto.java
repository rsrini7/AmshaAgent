package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Transfer object representing durable tool attempt state per Proposal §12.4.
 */
public record ToolAttemptDto(
        String workspaceId,
        String sessionId,
        String operationId,
        String attemptId,
        int attemptNumber,
        String toolName,
        String actionFingerprint,
        int fingerprintVersion,
        String targetFingerprint,
        ToolAttemptIdempotencyMode idempotencyModeSnapshot,
        ToolAttemptStatus status,
        long fencingToken,
        String executionKind,
        String executionHandle,
        String processStartIdentity,
        Integer exitCode,
        String sanitizedObservation,
        Instant createdAt,
        Instant launchRequestedAt,
        Instant runningAt,
        Instant settledAt) {

    private static final Pattern CANONICAL_UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public ToolAttemptDto {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        validateCanonicalUuid(operationId, "operationId");
        validateCanonicalUuid(attemptId, "attemptId");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive: " + attemptNumber);
        }
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        Objects.requireNonNull(actionFingerprint, "actionFingerprint must not be null");
        if (actionFingerprint.isBlank()) {
            throw new IllegalArgumentException("actionFingerprint must not be blank");
        }
        if (fingerprintVersion < 1) {
            throw new IllegalArgumentException("fingerprintVersion must be positive: " + fingerprintVersion);
        }
        if (!ToolAttemptFingerprintFormat.isValidActionFingerprint(actionFingerprint, fingerprintVersion)) {
            throw new IllegalArgumentException(
                    "actionFingerprint must be a canonical versioned SHA-256 digest matching fingerprintVersion");
        }
        if (targetFingerprint != null && !ToolAttemptFingerprintFormat.isValidTargetFingerprint(targetFingerprint)) {
            throw new IllegalArgumentException("targetFingerprint must be a canonical V1 SHA-256 digest");
        }
        Objects.requireNonNull(idempotencyModeSnapshot, "idempotencyModeSnapshot must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be non-negative: " + fencingToken);
        }
        Objects.requireNonNull(executionKind, "executionKind must not be null");
        if (executionKind.isBlank()) {
            throw new IllegalArgumentException("executionKind must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (status.isTerminal() && settledAt == null) {
            throw new IllegalArgumentException("settledAt must not be null for terminal status: " + status);
        }
        if (!status.isTerminal() && settledAt != null) {
            throw new IllegalArgumentException("settledAt must be null for non-terminal status: " + status);
        }
        if (status == ToolAttemptStatus.LAUNCH_REQUESTED && launchRequestedAt == null) {
            throw new IllegalArgumentException("launchRequestedAt must not be null for LAUNCH_REQUESTED status");
        }
        if (status == ToolAttemptStatus.RUNNING && (launchRequestedAt == null || runningAt == null)) {
            throw new IllegalArgumentException("RUNNING status requires launchRequestedAt and runningAt");
        }
        if ((status == ToolAttemptStatus.SETTLED_SUCCESS
                        || status == ToolAttemptStatus.SETTLED_ERROR
                        || status == ToolAttemptStatus.TIMED_OUT
                        || status == ToolAttemptStatus.INDETERMINATE)
                && launchRequestedAt == null) {
            throw new IllegalArgumentException("terminal execution status requires launchRequestedAt");
        }
        if (status == ToolAttemptStatus.ADMITTED && (launchRequestedAt != null || runningAt != null)) {
            throw new IllegalArgumentException("ADMITTED status cannot have launch or running timestamps");
        }
        if ((status == ToolAttemptStatus.LAUNCH_REQUESTED || status == ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH)
                && runningAt != null) {
            throw new IllegalArgumentException("pre-running status cannot have runningAt");
        }
        if (status == ToolAttemptStatus.SETTLED_SUCCESS && sanitizedObservation == null) {
            throw new IllegalArgumentException("sanitizedObservation must not be null for SETTLED_SUCCESS");
        }
    }

    private static void validateCanonicalUuid(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (!CANONICAL_UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a canonical lowercase UUID: " + value);
        }
    }
}
