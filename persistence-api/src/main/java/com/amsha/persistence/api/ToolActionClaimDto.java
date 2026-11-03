package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Transfer object representing an AT_MOST_ONCE tool action claim per Proposal §12.4.
 */
public record ToolActionClaimDto(
        String workspaceId,
        String sessionId,
        String toolName,
        int fingerprintVersion,
        String actionFingerprint,
        ActionClaimStatus status,
        String operationId,
        String attemptId,
        Instant createdAt,
        Instant consumedAt) {

    private static final Pattern CANONICAL_UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public ToolActionClaimDto {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (fingerprintVersion < 1) {
            throw new IllegalArgumentException("fingerprintVersion must be positive: " + fingerprintVersion);
        }
        Objects.requireNonNull(actionFingerprint, "actionFingerprint must not be null");
        if (actionFingerprint.isBlank()) {
            throw new IllegalArgumentException("actionFingerprint must not be blank");
        }
        if (!ToolAttemptFingerprintFormat.isValidActionFingerprint(actionFingerprint, fingerprintVersion)) {
            throw new IllegalArgumentException(
                    "actionFingerprint must be a canonical versioned SHA-256 digest matching fingerprintVersion");
        }
        Objects.requireNonNull(status, "status must not be null");
        validateCanonicalUuid(operationId, "operationId");
        validateCanonicalUuid(attemptId, "attemptId");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (status == ActionClaimStatus.CONSUMED) {
            if (consumedAt == null) {
                throw new IllegalArgumentException("consumedAt must not be null for CONSUMED status");
            }
        } else {
            if (consumedAt != null) {
                throw new IllegalArgumentException("consumedAt must be null for RESERVED status");
            }
        }
    }

    private static void validateCanonicalUuid(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (!CANONICAL_UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a canonical lowercase UUID: " + value);
        }
    }
}
