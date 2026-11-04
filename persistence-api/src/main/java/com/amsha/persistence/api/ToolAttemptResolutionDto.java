package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
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

    /** Standard resolution kind for fresh one-shot retry authorizations (§8.1, §12.4). */
    public static final String KIND_ONE_SHOT_RETRY = "ONE_SHOT_RETRY";

    /** Standard resolution kind for read-only postcondition verification of target resources (§8.1). */
    public static final String KIND_VERIFIED_POSTCONDITION = "VERIFIED_POSTCONDITION";

    /** Standard resolution kind for operator acknowledgement of indeterminate session uncertainty (§8.1). */
    public static final String KIND_OPERATOR_ACKNOWLEDGEMENT = "OPERATOR_ACKNOWLEDGEMENT";

    private static final Set<String> SUPPORTED_RESOLUTION_KINDS =
            Set.of(KIND_ONE_SHOT_RETRY, KIND_VERIFIED_POSTCONDITION, KIND_OPERATOR_ACKNOWLEDGEMENT);

    /**
     * Returns true if this resolution represents a one-shot retry authorization that must be
     * consumed exclusively via {@link ToolAttemptStorePort#applyResolutionAndAdmitRetry}.
     * {@link ToolAttemptStorePort#applyResolution} must reject retry authorizations.
     */
    public boolean isRetryAuthorization() {
        return KIND_ONE_SHOT_RETRY.equals(resolutionKind);
    }

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
        if (!SUPPORTED_RESOLUTION_KINDS.contains(resolutionKind)) {
            throw new IllegalArgumentException("Unsupported resolutionKind");
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

    /**
     * Validates that the provided authorization outbox event matches this resolution record.
     *
     * @param outbox authorization audit outbox record to validate
     */
    public void validateAuthorizationOutbox(ToolAttemptAuditOutboxDto outbox) {
        Objects.requireNonNull(outbox, "authorizationOutboxEvent must not be null");
        if (!authorizationOutboxEventId.equals(outbox.eventId())) {
            throw new IllegalArgumentException("Authorization outbox event ID mismatch: " + authorizationOutboxEventId
                    + " vs " + outbox.eventId());
        }
        if (!resolutionId.equals(outbox.resolutionId())) {
            throw new IllegalArgumentException("Authorization outbox event resolutionId does not match resolutionId: "
                    + outbox.resolutionId() + " vs " + resolutionId);
        }
        if (outbox.eventKind() != ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED) {
            throw new IllegalArgumentException(
                    "Authorization outbox event kind must be ACTION_RESOLUTION_AUTHORIZED: " + outbox.eventKind());
        }
        if (!workspaceId.equals(outbox.workspaceId())) {
            throw new IllegalArgumentException(
                    "Authorization outbox event workspaceId does not match resolution: " + outbox.workspaceId());
        }
    }
}
