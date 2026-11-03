package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Input request for attempt admission per Proposal §12.4.
 */
public record ToolAttemptAdmissionRequest(
        String workspaceId,
        String sessionId,
        String operationId,
        String retryOfAttemptId,
        String toolName,
        String actionFingerprint,
        int fingerprintVersion,
        String targetFingerprint,
        ToolAttemptIdempotencyMode idempotencyMode,
        String executionKind,
        String admissionSafeMetadataJson,
        Instant createdAt) {

    private static final Pattern CANONICAL_UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public ToolAttemptAdmissionRequest {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        validateCanonicalUuid(operationId, "operationId");
        if (retryOfAttemptId != null) {
            validateCanonicalUuid(retryOfAttemptId, "retryOfAttemptId");
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
        Objects.requireNonNull(idempotencyMode, "idempotencyMode must not be null");
        Objects.requireNonNull(executionKind, "executionKind must not be null");
        if (executionKind.isBlank()) {
            throw new IllegalArgumentException("executionKind must not be blank");
        }
        Objects.requireNonNull(admissionSafeMetadataJson, "admissionSafeMetadataJson must not be null");
        if (admissionSafeMetadataJson.isBlank()) {
            throw new IllegalArgumentException("admissionSafeMetadataJson must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void validateCanonicalUuid(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (!CANONICAL_UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a canonical lowercase UUID: " + value);
        }
    }
}
