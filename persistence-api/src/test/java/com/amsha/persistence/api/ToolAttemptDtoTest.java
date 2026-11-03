package com.amsha.persistence.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying ToolAttemptDto invariants and evidence rules per Proposal §12.3 and §12.4.
 */
class ToolAttemptDtoTest {

    private static final String VALID_UUID_1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String VALID_UUID_2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ACTION_FINGERPRINT =
            "sha256:v1:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void testToolAttemptDtoValidActiveAndSettled() {
        Instant now = Instant.now();

        // Valid ADMITTED
        ToolAttemptDto admitted = new ToolAttemptDto(
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
        assertEquals("ws-1", admitted.workspaceId());
        assertEquals(ToolAttemptStatus.ADMITTED, admitted.status());
        assertNull(admitted.settledAt());

        // Valid SETTLED_SUCCESS
        ToolAttemptDto settled = new ToolAttemptDto(
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
                ToolAttemptStatus.SETTLED_SUCCESS,
                1L,
                "BUILTIN",
                null,
                null,
                0,
                "file contents",
                now,
                now,
                now,
                now);
        assertEquals(ToolAttemptStatus.SETTLED_SUCCESS, settled.status());
        assertNotNull(settled.settledAt());
    }

    @Test
    void testToolAttemptDtoRequiresLifecycleEvidenceAndAllowsDirectSettlement() {
        Instant now = Instant.now();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptDto(
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
                        0L,
                        "BUILTIN",
                        null,
                        null,
                        null,
                        null,
                        now,
                        null,
                        null,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptDto(
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
                        ToolAttemptStatus.RUNNING,
                        1L,
                        "BUILTIN",
                        null,
                        null,
                        null,
                        null,
                        now,
                        now,
                        null,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptDto(
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
                        ToolAttemptStatus.SETTLED_SUCCESS,
                        1L,
                        "BUILTIN",
                        null,
                        null,
                        0,
                        null,
                        now,
                        now,
                        null,
                        now));

        ToolAttemptDto directSettlement = new ToolAttemptDto(
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
                ToolAttemptStatus.SETTLED_SUCCESS,
                1L,
                "BUILTIN",
                null,
                null,
                0,
                "completed",
                now,
                now,
                null,
                now);
        assertNull(directSettlement.runningAt());
        assertEquals(ToolAttemptStatus.SETTLED_SUCCESS, directSettlement.status());
    }

    @Test
    void testToolAttemptDtoValidationFailures() {
        Instant now = Instant.now();

        // Non-canonical UUID for operationId
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptDto(
                        "ws-1",
                        "sess-1",
                        "not-a-uuid",
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
                        null));

        // Uppercase UUID rejected (must be canonical lowercase per §12.2)
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptDto(
                        "ws-1",
                        "sess-1",
                        VALID_UUID_1.toUpperCase(Locale.ROOT),
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
                        null));

        // attemptNumber < 1
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptDto(
                        "ws-1",
                        "sess-1",
                        VALID_UUID_1,
                        VALID_UUID_2,
                        0,
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
                        null));

        // negative fencingToken
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptDto(
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
                        -1L,
                        "BUILTIN",
                        null,
                        null,
                        null,
                        null,
                        now,
                        null,
                        null,
                        null));

        // terminal status with null settledAt
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptDto(
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
                        ToolAttemptStatus.SETTLED_SUCCESS,
                        1L,
                        "BUILTIN",
                        null,
                        null,
                        0,
                        "done",
                        now,
                        now,
                        now,
                        null));

        // non-terminal status with non-null settledAt
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptDto(
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
                        now));
    }

    // =========================================================================
    // 6. DurableActionLockDto Invariant Tests (§12.4)
    // =========================================================================
}
