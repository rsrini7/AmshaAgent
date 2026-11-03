package com.amsha.persistence.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying admission and transition request/result contracts per Proposal §12.4.
 */
class ToolAttemptAdmissionAndTransitionTest {

    private static final String VALID_UUID_1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String VALID_UUID_2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String VALID_UUID_3 = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String ACTION_FINGERPRINT =
            "sha256:v1:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String TARGET_FINGERPRINT =
            "target:sha256:v1:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void testAdmissionRequestAndFingerprintFormats() {
        Instant now = Instant.now();
        ToolAttemptAdmissionRequest request = new ToolAttemptAdmissionRequest(
                "ws-1",
                "sess-1",
                VALID_UUID_1,
                null,
                "agent_task",
                ACTION_FINGERPRINT,
                1,
                TARGET_FINGERPRINT,
                ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                "BUILTIN",
                "{}",
                now);
        assertEquals("{}", request.admissionSafeMetadataJson());
        ToolAttemptAdmissionRequest retryRequest = new ToolAttemptAdmissionRequest(
                "ws-1",
                "sess-1",
                VALID_UUID_1,
                VALID_UUID_2,
                "agent_task",
                ACTION_FINGERPRINT,
                1,
                TARGET_FINGERPRINT,
                ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                "BUILTIN",
                "{}",
                now);
        assertEquals(VALID_UUID_2, retryRequest.retryOfAttemptId());
        assertTrue(ToolAttemptFingerprintFormat.isValidActionFingerprint(ACTION_FINGERPRINT, 1));
        assertTrue(ToolAttemptFingerprintFormat.isValidTargetFingerprint(TARGET_FINGERPRINT));
        assertFalse(ToolAttemptFingerprintFormat.isValidActionFingerprint(ACTION_FINGERPRINT, 2));
        assertFalse(ToolAttemptFingerprintFormat.isValidActionFingerprint("sha256:v1:ABC", 1));
        assertFalse(ToolAttemptFingerprintFormat.isValidActionFingerprint("sha256:v01:" + "a".repeat(64)));
        assertFalse(ToolAttemptFingerprintFormat.isValidTargetFingerprint("target:sha256:v1:ABC"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAdmissionRequest(
                        "ws-1",
                        "sess-1",
                        VALID_UUID_1,
                        null,
                        "agent_task",
                        ACTION_FINGERPRINT,
                        2,
                        TARGET_FINGERPRINT,
                        ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                        "BUILTIN",
                        "{}",
                        now));
        assertThrows(
                NullPointerException.class,
                () -> new ToolAttemptAdmissionRequest(
                        "ws-1",
                        "sess-1",
                        VALID_UUID_1,
                        null,
                        "agent_task",
                        ACTION_FINGERPRINT,
                        1,
                        null,
                        ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                        "BUILTIN",
                        null,
                        now));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAdmissionRequest(
                        "ws-1",
                        "sess-1",
                        VALID_UUID_1,
                        "not-a-uuid",
                        "agent_task",
                        ACTION_FINGERPRINT,
                        1,
                        TARGET_FINGERPRINT,
                        ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                        "BUILTIN",
                        "{}",
                        now));
    }

    // =========================================================================
    // 11. AuditChainFinalizer & Default Port Method Tests (§12.5)
    // =========================================================================

    @Test
    void testAdmissionResults() {
        Instant now = Instant.now();
        ToolAttemptDto attempt = new ToolAttemptDto(
                "ws-1",
                "sess-1",
                VALID_UUID_1,
                VALID_UUID_2,
                1,
                "read_file",
                ACTION_FINGERPRINT,
                1,
                null,
                ToolAttemptIdempotencyMode.IDEMPOTENT,
                ToolAttemptStatus.ADMITTED,
                0L,
                "BUILTIN",
                null,
                null,
                null,
                null,
                now,
                null,
                null,
                null);

        ToolAttemptAuditOutboxDto admissionEvent = ToolAttemptAuditOutboxDto.forAttempt(
                VALID_UUID_3,
                attempt.workspaceId(),
                attempt.sessionId(),
                attempt.attemptId(),
                ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                "{}",
                now);
        ToolAttemptAdmissionResult created = ToolAttemptAdmissionResult.created(attempt, admissionEvent);
        assertEquals(ToolAttemptAdmissionResult.AdmissionStatus.CREATED, created.status());
        assertEquals(attempt, created.attempt());
        assertEquals(admissionEvent, created.admissionOutboxEvent());

        ToolAttemptAdmissionResult identical = ToolAttemptAdmissionResult.identicalExisting(attempt, admissionEvent);
        assertEquals(ToolAttemptAdmissionResult.AdmissionStatus.IDENTICAL_EXISTING, identical.status());

        ToolAttemptDto atMostOnceAttempt = new ToolAttemptDto(
                "ws-1",
                "sess-1",
                VALID_UUID_1,
                VALID_UUID_2,
                1,
                "read_file",
                ACTION_FINGERPRINT,
                1,
                null,
                ToolAttemptIdempotencyMode.AT_MOST_ONCE,
                ToolAttemptStatus.SETTLED_SUCCESS,
                2L,
                "BUILTIN",
                null,
                null,
                0,
                "completed",
                now,
                now,
                null,
                now);
        ToolAttemptAdmissionResult atMostOnce = ToolAttemptAdmissionResult.atMostOnceExisting(atMostOnceAttempt);
        assertEquals(ToolAttemptAdmissionResult.AdmissionStatus.AT_MOST_ONCE_EXISTING, atMostOnce.status());
        assertNull(atMostOnce.admissionOutboxEvent());

        ToolAttemptAdmissionResult conflict = ToolAttemptAdmissionResult.conflict("Fencing conflict");
        assertEquals(ToolAttemptAdmissionResult.AdmissionStatus.CONFLICT, conflict.status());
        assertEquals("Fencing conflict", conflict.message());
        assertNull(conflict.attempt());

        assertThrows(NullPointerException.class, () -> ToolAttemptAdmissionResult.created(attempt, null));
        assertThrows(NullPointerException.class, () -> ToolAttemptAdmissionResult.conflict(null));
        assertThrows(NullPointerException.class, () -> ToolAttemptAdmissionResult.identicalExisting(attempt, null));
        assertThrows(IllegalArgumentException.class, () -> ToolAttemptAdmissionResult.atMostOnceExisting(attempt));
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolAttemptAdmissionResult.atMostOnceExisting(new ToolAttemptDto(
                        "ws-1",
                        "sess-1",
                        VALID_UUID_1,
                        VALID_UUID_2,
                        1,
                        "read_file",
                        ACTION_FINGERPRINT,
                        1,
                        null,
                        ToolAttemptIdempotencyMode.AT_MOST_ONCE,
                        ToolAttemptStatus.INDETERMINATE,
                        2L,
                        "BUILTIN",
                        null,
                        null,
                        null,
                        null,
                        now,
                        now,
                        null,
                        now)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAdmissionResult(
                        ToolAttemptAdmissionResult.AdmissionStatus.AT_MOST_ONCE_EXISTING,
                        atMostOnceAttempt,
                        admissionEvent,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAdmissionResult(
                        ToolAttemptAdmissionResult.AdmissionStatus.CONFLICT, attempt, null, "conflict"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAdmissionResult(
                        ToolAttemptAdmissionResult.AdmissionStatus.CREATED, attempt, admissionEvent, "extra"));
    }

    @Test
    void testTransitionResults() {
        Instant now = Instant.now();
        ToolAttemptDto updated = new ToolAttemptDto(
                "ws-1",
                "sess-1",
                VALID_UUID_1,
                VALID_UUID_2,
                1,
                "read_file",
                ACTION_FINGERPRINT,
                1,
                null,
                ToolAttemptIdempotencyMode.IDEMPOTENT,
                ToolAttemptStatus.LAUNCH_REQUESTED,
                1L,
                "BUILTIN",
                null,
                null,
                null,
                null,
                now,
                now,
                null,
                null);

        ToolAttemptTransitionResult applied = ToolAttemptTransitionResult.applied(updated);
        assertEquals(ToolAttemptTransitionResult.TransitionStatus.APPLIED, applied.status());
        assertEquals(updated, applied.updatedAttempt());

        ToolAttemptTransitionResult stale = ToolAttemptTransitionResult.stale("Stale fencing token");
        assertEquals(ToolAttemptTransitionResult.TransitionStatus.STALE, stale.status());
        assertEquals("Stale fencing token", stale.message());

        ToolAttemptTransitionResult conflict = ToolAttemptTransitionResult.conflict("Status conflict");
        assertEquals(ToolAttemptTransitionResult.TransitionStatus.CONFLICT, conflict.status());
        assertEquals("Status conflict", conflict.message());

        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptTransitionResult(
                        ToolAttemptTransitionResult.TransitionStatus.APPLIED, updated, "extra"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptTransitionResult(
                        ToolAttemptTransitionResult.TransitionStatus.STALE, updated, "stale"));
    }
}
