package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Transfer object representing an authorized resolution per Proposal §12.4 and §12.7.
 */
public record ToolAttemptResolutionDto(
        String resolutionId,
        String lockId,
        String workspaceId,
        String actorId,
        String resolutionKind,
        ToolAttemptResolutionStatus resolutionStatus,
        String originAttemptId,
        String actionFingerprint,
        String targetFingerprint,
        String evidenceReferenceOrDigest,
        String authorizationOutboxEventId,
        String resolvedOutboxEventId,
        Instant createdAt,
        Instant consumedAt) {

    private static final Pattern CANONICAL_UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public ToolAttemptResolutionDto {
        validateCanonicalUuid(resolutionId, "resolutionId");
        validateCanonicalUuid(lockId, "lockId");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        Objects.requireNonNull(actorId, "actorId must not be null");
        if (actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        Objects.requireNonNull(resolutionKind, "resolutionKind must not be null");
        if (resolutionKind.isBlank()) {
            throw new IllegalArgumentException("resolutionKind must not be blank");
        }
        Objects.requireNonNull(resolutionStatus, "resolutionStatus must not be null");
        validateCanonicalUuid(originAttemptId, "originAttemptId");
        Objects.requireNonNull(actionFingerprint, "actionFingerprint must not be null");
        if (actionFingerprint.isBlank()) {
            throw new IllegalArgumentException("actionFingerprint must not be blank");
        }
        if (!ToolAttemptFingerprintFormat.isValidActionFingerprint(actionFingerprint)) {
            throw new IllegalArgumentException("actionFingerprint must be a canonical versioned SHA-256 digest");
        }
        if (targetFingerprint != null && !ToolAttemptFingerprintFormat.isValidTargetFingerprint(targetFingerprint)) {
            throw new IllegalArgumentException("targetFingerprint must be a canonical V1 SHA-256 digest");
        }
        Objects.requireNonNull(evidenceReferenceOrDigest, "evidenceReferenceOrDigest must not be null");
        if (evidenceReferenceOrDigest.isBlank()) {
            throw new IllegalArgumentException("evidenceReferenceOrDigest must not be blank");
        }
        validateCanonicalUuid(authorizationOutboxEventId, "authorizationOutboxEventId");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (resolvedOutboxEventId != null) {
            validateCanonicalUuid(resolvedOutboxEventId, "resolvedOutboxEventId");
        }

        boolean applied = resolutionStatus == ToolAttemptResolutionStatus.APPLIED;
        if (applied != (consumedAt != null)) {
            throw new IllegalArgumentException("consumedAt must be present if and only if resolution is APPLIED");
        }
        if (applied != (resolvedOutboxEventId != null)) {
            throw new IllegalArgumentException(
                    "resolvedOutboxEventId must be present if and only if resolution is APPLIED");
        }
    }

    private static void validateCanonicalUuid(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (!CANONICAL_UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a canonical lowercase UUID: " + value);
        }
    }
}
