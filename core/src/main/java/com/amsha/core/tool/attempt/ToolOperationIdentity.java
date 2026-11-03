package com.amsha.core.tool.attempt;

import java.util.Objects;
import java.util.UUID;

import com.amsha.persistence.api.ActionLockScope;

/**
 * Immutable identity model for a tool call operation and its execution attempt per Proposal §4.1 and §12.2.
 *
 * <p>Carries the stable {@code operationId} created at the orchestration boundary, the attempt identity
 * allocated during admission, action and target fingerprints, and the resolved lock scope.
 *
 * @param workspaceId non-blank workspace identity
 * @param sessionId non-blank session identity
 * @param toolName non-blank canonical tool name
 * @param operationId canonical lowercase UUID string identifying the logical operation across turns/resumes
 * @param attemptId canonical lowercase UUID string identifying this specific execution attempt
 *                  (nullable before store admission)
 * @param attemptNumber monotonically increasing attempt counter (0 before admission, 1 for initial attempt)
 * @param actionFingerprint versioned action fingerprint {@code sha256:v1:<hex>}
 * @param fingerprintVersion action fingerprint version (1)
 * @param targetFingerprint trusted target fingerprint {@code target:sha256:v1:<hex>}, or null for SESSION_MUTATIONS
 * @param lockScope resolved lock scope (TARGET_RESOURCE or SESSION_MUTATIONS)
 */
public record ToolOperationIdentity(
        String workspaceId,
        String sessionId,
        String toolName,
        String operationId,
        String attemptId,
        int attemptNumber,
        String actionFingerprint,
        int fingerprintVersion,
        String targetFingerprint,
        ActionLockScope lockScope) {

    public static final int CURRENT_FINGERPRINT_VERSION = 1;

    public ToolOperationIdentity {
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
        Objects.requireNonNull(operationId, "operationId must not be null");
        validateCanonicalUuid(operationId, "operationId");

        if (fingerprintVersion <= 0) {
            throw new IllegalArgumentException("fingerprintVersion must be positive: " + fingerprintVersion);
        }
        Objects.requireNonNull(actionFingerprint, "actionFingerprint must not be null");
        String expectedPrefix = "sha256:v" + fingerprintVersion + ":";
        if (!actionFingerprint.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("actionFingerprint must start with " + expectedPrefix
                    + " to match fingerprintVersion " + fingerprintVersion + ": " + actionFingerprint);
        }
        String actionDigest = actionFingerprint.substring(expectedPrefix.length());
        if (!CanonicalJsonUtil.isLowercaseHex64(actionDigest)) {
            throw new IllegalArgumentException(
                    "actionFingerprint must contain a 64-character lowercase hex digest: " + actionFingerprint);
        }

        if (attemptId == null) {
            if (attemptNumber != 0) {
                throw new IllegalArgumentException(
                        "attemptNumber must be 0 when attemptId is null (unadmitted operation), got: " + attemptNumber);
            }
        } else {
            validateCanonicalUuid(attemptId, "attemptId");
            if (attemptNumber <= 0) {
                throw new IllegalArgumentException(
                        "attemptNumber must be positive when attemptId is present (admitted attempt), got: "
                                + attemptNumber);
            }
        }

        Objects.requireNonNull(lockScope, "lockScope must not be null");
        if (lockScope == ActionLockScope.TARGET_RESOURCE) {
            if (targetFingerprint == null || targetFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "targetFingerprint must not be null or blank when lockScope is TARGET_RESOURCE");
            }
            if (!targetFingerprint.startsWith(ToolTargetResolver.TARGET_PREFIX)) {
                throw new IllegalArgumentException("targetFingerprint must start with "
                        + ToolTargetResolver.TARGET_PREFIX + ": " + targetFingerprint);
            }
            String targetDigest = targetFingerprint.substring(ToolTargetResolver.TARGET_PREFIX.length());
            if (!CanonicalJsonUtil.isLowercaseHex64(targetDigest)) {
                throw new IllegalArgumentException(
                        "targetFingerprint must contain a 64-character lowercase hex digest: " + targetFingerprint);
            }
        } else if (lockScope == ActionLockScope.SESSION_MUTATIONS || lockScope == ActionLockScope.EXACT_REQUEST) {
            if (targetFingerprint != null) {
                throw new IllegalArgumentException("targetFingerprint must be null when lockScope is " + lockScope);
            }
        }
    }

    /**
     * Generates a canonical lowercase UUID string.
     */
    public static String generateCanonicalUuid() {
        return UUID.randomUUID().toString().toLowerCase();
    }

    /**
     * Validates that the provided string is a valid canonical lowercase UUID.
     */
    public static void validateCanonicalUuid(String uuid, String fieldName) {
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        if (!uuid.equals(uuid.toLowerCase())) {
            throw new IllegalArgumentException(fieldName + " must be canonical lowercase: " + uuid);
        }
        try {
            UUID parsed = UUID.fromString(uuid);
            if (!parsed.toString().equals(uuid)) {
                throw new IllegalArgumentException(fieldName + " is not a canonical UUID: " + uuid);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " is not a valid UUID: " + uuid, e);
        }
    }

    /**
     * Factory for an unadmitted operation created at the orchestration boundary.
     */
    public static ToolOperationIdentity create(
            String workspaceId,
            String sessionId,
            String toolName,
            String actionFingerprint,
            ToolTargetResolution targetResolution) {
        return create(workspaceId, sessionId, toolName, generateCanonicalUuid(), actionFingerprint, targetResolution);
    }

    /**
     * Factory for an unadmitted operation with a known operationId.
     */
    public static ToolOperationIdentity create(
            String workspaceId,
            String sessionId,
            String toolName,
            String operationId,
            String actionFingerprint,
            ToolTargetResolution targetResolution) {
        Objects.requireNonNull(targetResolution, "targetResolution must not be null");
        return new ToolOperationIdentity(
                workspaceId,
                sessionId,
                toolName,
                operationId,
                null,
                0,
                actionFingerprint,
                CURRENT_FINGERPRINT_VERSION,
                targetResolution.targetFingerprint(),
                targetResolution.scope());
    }

    /**
     * Returns an admitted attempt identity carrying the store-allocated attempt ID and attempt number.
     *
     * @param allocatedAttemptId canonical lowercase UUID allocated by the store
     * @param allocatedAttemptNumber attempt number allocated by the store (must be positive)
     * @return admitted {@link ToolOperationIdentity}
     */
    public ToolOperationIdentity withAttempt(String allocatedAttemptId, int allocatedAttemptNumber) {
        if (allocatedAttemptId == null) {
            throw new IllegalArgumentException("allocatedAttemptId must not be null");
        }
        if (allocatedAttemptNumber <= 0) {
            throw new IllegalArgumentException("Admitted attemptNumber must be positive: " + allocatedAttemptNumber);
        }
        return new ToolOperationIdentity(
                workspaceId,
                sessionId,
                toolName,
                operationId,
                allocatedAttemptId,
                allocatedAttemptNumber,
                actionFingerprint,
                fingerprintVersion,
                targetFingerprint,
                lockScope);
    }

    /**
     * Returns a new attempt identity for a retry, preserving the original operation ID and setting the
     * store-allocated attempt ID and attempt number per Proposal §12.2.
     *
     * @param allocatedAttemptId canonical lowercase UUID allocated by the store
     * @param allocatedAttemptNumber attempt number allocated by the store (must be greater than current attemptNumber)
     * @return new {@link ToolOperationIdentity} for the retry attempt
     */
    public ToolOperationIdentity forRetry(String allocatedAttemptId, int allocatedAttemptNumber) {
        if (allocatedAttemptNumber <= this.attemptNumber) {
            throw new IllegalArgumentException("Store-allocated retry attemptNumber (" + allocatedAttemptNumber
                    + ") must be greater than current attemptNumber (" + this.attemptNumber + ")");
        }
        return withAttempt(allocatedAttemptId, allocatedAttemptNumber);
    }
}
