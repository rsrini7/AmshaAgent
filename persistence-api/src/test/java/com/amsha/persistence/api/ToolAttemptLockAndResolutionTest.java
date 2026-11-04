package com.amsha.persistence.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying durable action lock, claim, and resolution invariants per Proposal §8, §12.4, and §12.7.
 */
class ToolAttemptLockAndResolutionTest {

    private static final String VALID_UUID_1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String VALID_UUID_2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String VALID_UUID_3 = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String VALID_UUID_4 = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String VALID_UUID_5 = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String ACTION_FINGERPRINT =
            "sha256:v1:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String TARGET_FINGERPRINT =
            "target:sha256:v1:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void testDurableActionLockDtoTargetResourceInvariants() {
        Instant now = Instant.now();

        // Valid TARGET_RESOURCE lock
        DurableActionLockDto lock = new DurableActionLockDto(
                VALID_UUID_1,
                "ws-1",
                "sess-1",
                VALID_UUID_2,
                VALID_UUID_3,
                ACTION_FINGERPRINT,
                TARGET_FINGERPRINT,
                ActionLockScope.TARGET_RESOURCE,
                "Resource lock",
                DurableActionLockStatus.ACTIVE,
                now,
                null,
                null);
        assertEquals(ActionLockScope.TARGET_RESOURCE, lock.scope());
        assertEquals(TARGET_FINGERPRINT, lock.targetFingerprint());

        // TARGET_RESOURCE with null targetFingerprint rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new DurableActionLockDto(
                        VALID_UUID_1,
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        VALID_UUID_3,
                        ACTION_FINGERPRINT,
                        null,
                        ActionLockScope.TARGET_RESOURCE,
                        "Resource lock",
                        DurableActionLockStatus.ACTIVE,
                        now,
                        null,
                        null));

        // SESSION_MUTATIONS with targetFingerprint rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new DurableActionLockDto(
                        VALID_UUID_1,
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        VALID_UUID_3,
                        ACTION_FINGERPRINT,
                        TARGET_FINGERPRINT,
                        ActionLockScope.SESSION_MUTATIONS,
                        "Session lock",
                        DurableActionLockStatus.ACTIVE,
                        now,
                        null,
                        null));

        // CLEARED status with null clearedAt rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new DurableActionLockDto(
                        VALID_UUID_1,
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        VALID_UUID_3,
                        ACTION_FINGERPRINT,
                        TARGET_FINGERPRINT,
                        ActionLockScope.TARGET_RESOURCE,
                        "Resource lock",
                        DurableActionLockStatus.CLEARED,
                        now,
                        null,
                        VALID_UUID_4));

        // ACTIVE status with non-null clearedAt rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new DurableActionLockDto(
                        VALID_UUID_1,
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        VALID_UUID_3,
                        ACTION_FINGERPRINT,
                        TARGET_FINGERPRINT,
                        ActionLockScope.TARGET_RESOURCE,
                        "Resource lock",
                        DurableActionLockStatus.ACTIVE,
                        now,
                        now,
                        null));
    }

    // =========================================================================
    // 7. ToolAttemptAuditOutboxDto Invariant Tests (§12.4)
    // =========================================================================

    @Test
    void testClearedActionLockRequiresResolutionId() {
        Instant now = Instant.now();
        assertThrows(
                IllegalArgumentException.class,
                () -> new DurableActionLockDto(
                        VALID_UUID_1,
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        VALID_UUID_3,
                        ACTION_FINGERPRINT,
                        TARGET_FINGERPRINT,
                        ActionLockScope.TARGET_RESOURCE,
                        "Resource lock",
                        DurableActionLockStatus.CLEARED,
                        now,
                        now,
                        null));
    }

    @Test
    void testToolAttemptResolutionDtoInvariants() {
        Instant now = Instant.now();

        // Valid PENDING resolution
        ToolAttemptResolutionDto pending = new ToolAttemptResolutionDto(
                VALID_UUID_1,
                VALID_UUID_2,
                "ws-1",
                "actor-1",
                ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION,
                ToolAttemptResolutionStatus.PENDING,
                VALID_UUID_3,
                ACTION_FINGERPRINT,
                TARGET_FINGERPRINT,
                "sha256:evidence",
                VALID_UUID_4,
                null,
                now,
                null);
        assertEquals(ToolAttemptResolutionStatus.PENDING, pending.resolutionStatus());
        assertNull(pending.consumedAt());

        // PENDING with non-null consumedAt rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptResolutionDto(
                        VALID_UUID_1,
                        VALID_UUID_2,
                        "ws-1",
                        "actor-1",
                        ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION,
                        ToolAttemptResolutionStatus.PENDING,
                        VALID_UUID_3,
                        ACTION_FINGERPRINT,
                        TARGET_FINGERPRINT,
                        "sha256:evidence",
                        VALID_UUID_4,
                        null,
                        now,
                        now));

        // APPLIED with null consumedAt rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptResolutionDto(
                        VALID_UUID_1,
                        VALID_UUID_2,
                        "ws-1",
                        "actor-1",
                        ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION,
                        ToolAttemptResolutionStatus.APPLIED,
                        VALID_UUID_3,
                        ACTION_FINGERPRINT,
                        TARGET_FINGERPRINT,
                        "sha256:evidence",
                        VALID_UUID_4,
                        null,
                        now,
                        null));
    }

    // =========================================================================
    // 9. ToolActionClaimDto Invariant Tests (§12.4)
    // =========================================================================

    @Test
    void testResolutionStateRequiresResolvedOutboxLink() {
        Instant now = Instant.now();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptResolutionDto(
                        VALID_UUID_1,
                        VALID_UUID_2,
                        "ws-1",
                        "actor-1",
                        ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION,
                        ToolAttemptResolutionStatus.PENDING,
                        VALID_UUID_3,
                        ACTION_FINGERPRINT,
                        TARGET_FINGERPRINT,
                        "sha256:evidence",
                        VALID_UUID_4,
                        VALID_UUID_5,
                        now,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptResolutionDto(
                        VALID_UUID_1,
                        VALID_UUID_2,
                        "ws-1",
                        "actor-1",
                        ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION,
                        ToolAttemptResolutionStatus.EXPIRED,
                        VALID_UUID_3,
                        ACTION_FINGERPRINT,
                        TARGET_FINGERPRINT,
                        "sha256:evidence",
                        VALID_UUID_4,
                        null,
                        now,
                        now));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptResolutionDto(
                        VALID_UUID_1,
                        VALID_UUID_2,
                        "ws-1",
                        "actor-1",
                        ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION,
                        ToolAttemptResolutionStatus.APPLIED,
                        VALID_UUID_3,
                        ACTION_FINGERPRINT,
                        TARGET_FINGERPRINT,
                        "sha256:evidence",
                        VALID_UUID_4,
                        null,
                        now,
                        now));

        ToolAttemptResolutionDto applied = new ToolAttemptResolutionDto(
                VALID_UUID_1,
                VALID_UUID_2,
                "ws-1",
                "actor-1",
                ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION,
                ToolAttemptResolutionStatus.APPLIED,
                VALID_UUID_3,
                ACTION_FINGERPRINT,
                TARGET_FINGERPRINT,
                "sha256:evidence",
                VALID_UUID_4,
                VALID_UUID_5,
                now,
                now);
        assertEquals(VALID_UUID_5, applied.resolvedOutboxEventId());
    }

    @Test
    void testToolActionClaimDtoInvariants() {
        Instant now = Instant.now();

        // Valid RESERVED claim
        ToolActionClaimDto reserved = new ToolActionClaimDto(
                "ws-1",
                "sess-1",
                "agent_task",
                1,
                ACTION_FINGERPRINT,
                ActionClaimStatus.RESERVED,
                VALID_UUID_1,
                VALID_UUID_2,
                now,
                null);
        assertEquals(ActionClaimStatus.RESERVED, reserved.status());
        assertNull(reserved.consumedAt());

        // RESERVED with non-null consumedAt rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolActionClaimDto(
                        "ws-1",
                        "sess-1",
                        "agent_task",
                        1,
                        ACTION_FINGERPRINT,
                        ActionClaimStatus.RESERVED,
                        VALID_UUID_1,
                        VALID_UUID_2,
                        now,
                        now));

        // CONSUMED with null consumedAt rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolActionClaimDto(
                        "ws-1",
                        "sess-1",
                        "agent_task",
                        1,
                        ACTION_FINGERPRINT,
                        ActionClaimStatus.CONSUMED,
                        VALID_UUID_1,
                        VALID_UUID_2,
                        now,
                        null));
    }

    @Test
    void testResolutionDtoRetryAuthorizationClassification() {
        Instant now = Instant.now();

        ToolAttemptResolutionDto retryResolution = new ToolAttemptResolutionDto(
                VALID_UUID_1,
                VALID_UUID_2,
                "ws-1",
                "actor-1",
                ToolAttemptResolutionDto.KIND_ONE_SHOT_RETRY,
                ToolAttemptResolutionStatus.PENDING,
                VALID_UUID_3,
                ACTION_FINGERPRINT,
                TARGET_FINGERPRINT,
                "sha256:evidence",
                VALID_UUID_4,
                null,
                now,
                null);
        Assertions.assertTrue(retryResolution.isRetryAuthorization());

        ToolAttemptResolutionDto postconditionResolution = new ToolAttemptResolutionDto(
                VALID_UUID_1,
                VALID_UUID_2,
                "ws-1",
                "actor-1",
                ToolAttemptResolutionDto.KIND_VERIFIED_POSTCONDITION,
                ToolAttemptResolutionStatus.PENDING,
                VALID_UUID_3,
                ACTION_FINGERPRINT,
                TARGET_FINGERPRINT,
                "sha256:evidence",
                VALID_UUID_4,
                null,
                now,
                null);
        Assertions.assertFalse(postconditionResolution.isRetryAuthorization());

        ToolAttemptResolutionDto operatorResolution = new ToolAttemptResolutionDto(
                VALID_UUID_1,
                VALID_UUID_2,
                "ws-1",
                "actor-1",
                ToolAttemptResolutionDto.KIND_OPERATOR_ACKNOWLEDGEMENT,
                ToolAttemptResolutionStatus.PENDING,
                VALID_UUID_3,
                ACTION_FINGERPRINT,
                null,
                "sha256:evidence",
                VALID_UUID_4,
                null,
                now,
                null);
        Assertions.assertFalse(operatorResolution.isRetryAuthorization());
    }

    @Test
    void testResolutionDtoRejectsUnknownResolutionKinds() {
        assertThrows(IllegalArgumentException.class, () -> pendingResolution("UNRECOGNIZED_KIND"));
        assertThrows(IllegalArgumentException.class, () -> pendingResolution("human_operator_retry"));
        assertThrows(IllegalArgumentException.class, () -> pendingResolution("one_shot_retry"));
    }

    private static ToolAttemptResolutionDto pendingResolution(String resolutionKind) {
        return new ToolAttemptResolutionDto(
                VALID_UUID_1,
                VALID_UUID_2,
                "ws-1",
                "actor-1",
                resolutionKind,
                ToolAttemptResolutionStatus.PENDING,
                VALID_UUID_3,
                ACTION_FINGERPRINT,
                TARGET_FINGERPRINT,
                "sha256:evidence",
                VALID_UUID_4,
                null,
                Instant.now(),
                null);
    }

    // =========================================================================
    // 10. Admission & Transition Results Tests (§12.4)
    // =========================================================================
}
