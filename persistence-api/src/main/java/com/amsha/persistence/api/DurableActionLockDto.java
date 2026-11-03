package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Transfer object representing a durable action lock per Proposal §8 and §12.4.
 */
public record DurableActionLockDto(
        String lockId,
        String workspaceId,
        String sessionId,
        String originOperationId,
        String originAttemptId,
        String actionFingerprint,
        String targetFingerprint,
        ActionLockScope scope,
        String reason,
        DurableActionLockStatus status,
        Instant createdAt,
        Instant clearedAt,
        String resolutionId) {

    private static final Pattern CANONICAL_UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public DurableActionLockDto {
        validateCanonicalUuid(lockId, "lockId");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        validateCanonicalUuid(originOperationId, "originOperationId");
        validateCanonicalUuid(originAttemptId, "originAttemptId");
        Objects.requireNonNull(actionFingerprint, "actionFingerprint must not be null");
        if (actionFingerprint.isBlank()) {
            throw new IllegalArgumentException("actionFingerprint must not be blank");
        }
        if (!ToolAttemptFingerprintFormat.isValidActionFingerprint(actionFingerprint)) {
            throw new IllegalArgumentException("actionFingerprint must be a canonical versioned SHA-256 digest");
        }
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (scope == ActionLockScope.TARGET_RESOURCE) {
            if (!ToolAttemptFingerprintFormat.isValidTargetFingerprint(targetFingerprint)) {
                throw new IllegalArgumentException("targetFingerprint must be a canonical V1 SHA-256 digest");
            }
        } else {
            if (targetFingerprint != null) {
                throw new IllegalArgumentException("targetFingerprint must be null for scope: " + scope);
            }
        }

        if (status == DurableActionLockStatus.CLEARED) {
            if (clearedAt == null) {
                throw new IllegalArgumentException("clearedAt must not be null for CLEARED status");
            }
            if (resolutionId == null) {
                throw new IllegalArgumentException("resolutionId must not be null for CLEARED status");
            }
        } else {
            if (clearedAt != null) {
                throw new IllegalArgumentException("clearedAt must be null for ACTIVE status");
            }
        }

        if (resolutionId != null) {
            validateCanonicalUuid(resolutionId, "resolutionId");
        }
    }

    private static void validateCanonicalUuid(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (!CANONICAL_UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a canonical lowercase UUID: " + value);
        }
    }
}
