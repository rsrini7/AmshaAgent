package com.amsha.core.tool.attempt;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.amsha.persistence.api.ActionLockScope;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.DurableActionLockStatus;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptFingerprintFormat;

/**
 * Canonical factory and policy for creating durable action locks per Proposal §4.2, §8, and §12.2.
 * Unresolved targets strictly select the conservative SESSION_MUTATIONS lock scope.
 */
public final class ToolAttemptLockPolicy {

    private ToolAttemptLockPolicy() {}

    /**
     * Creates a durable action lock for an attempt using resolved tool target metadata.
     *
     * @param attempt the tool attempt
     * @param target the resolved target metadata (may be null)
     * @param reason the human/system reason for the lock
     * @param timestamp the creation timestamp
     * @return the created durable action lock DTO
     */
    public static DurableActionLockDto createLock(
            ToolAttemptDto attempt, ToolTargetResolution target, String reason, Instant timestamp) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        ActionLockScope scope;
        String targetFingerprint;

        if (target != null) {
            scope = target.scope();
            targetFingerprint = target.targetFingerprint();
        } else if (attempt.targetFingerprint() != null
                && ToolAttemptFingerprintFormat.isValidTargetFingerprint(attempt.targetFingerprint())) {
            scope = ActionLockScope.TARGET_RESOURCE;
            targetFingerprint = attempt.targetFingerprint();
        } else {
            scope = ActionLockScope.SESSION_MUTATIONS;
            targetFingerprint = null;
        }

        return new DurableActionLockDto(
                UUID.randomUUID().toString(),
                attempt.workspaceId(),
                attempt.sessionId(),
                attempt.operationId(),
                attempt.attemptId(),
                attempt.actionFingerprint(),
                targetFingerprint,
                scope,
                reason,
                DurableActionLockStatus.ACTIVE,
                timestamp,
                null,
                null);
    }

    /**
     * Creates a durable action lock for an attempt when target resolution metadata is not available
     * (e.g. during restart recovery or post-crash settlement). Unresolved targets select SESSION_MUTATIONS.
     */
    public static DurableActionLockDto createLock(ToolAttemptDto attempt, String reason, Instant timestamp) {
        return createLock(attempt, null, reason, timestamp);
    }
}
