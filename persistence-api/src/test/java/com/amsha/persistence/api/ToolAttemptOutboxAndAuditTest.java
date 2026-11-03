package com.amsha.persistence.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying outbox records and audit chain contracts per Proposal §12.4 and §12.5.
 */
class ToolAttemptOutboxAndAuditTest {

    private static final String VALID_UUID_1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String VALID_UUID_2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String VALID_UUID_3 = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String VALID_UUID_4 = "dddddddd-dddd-dddd-dddd-dddddddddddd";

    @Test
    void testAuditOutboxDtoFactoriesAndInvariants() {
        Instant now = Instant.now();

        // Attempt event factory
        ToolAttemptAuditOutboxDto attemptOutbox = ToolAttemptAuditOutboxDto.forAttempt(
                VALID_UUID_1, "ws-1", "sess-1", VALID_UUID_2, ToolAttemptAuditEventKind.ATTEMPT_ADMITTED, "{}", now);
        assertEquals("ws-1:" + VALID_UUID_2 + ":ATTEMPT_ADMITTED", attemptOutbox.idempotencyKey());
        assertEquals(VALID_UUID_2, attemptOutbox.attemptId());
        assertNull(attemptOutbox.resolutionId());

        // Resolution event factory
        ToolAttemptAuditOutboxDto resOutbox = ToolAttemptAuditOutboxDto.forResolution(
                VALID_UUID_1,
                "ws-1",
                "sess-1",
                VALID_UUID_3,
                ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED,
                "{}",
                now);
        assertEquals("ws-1:" + VALID_UUID_3 + ":ACTION_RESOLUTION_AUTHORIZED", resOutbox.idempotencyKey());
        assertEquals(VALID_UUID_3, resOutbox.resolutionId());
        assertNull(resOutbox.attemptId());

        // forAttempt with resolution event rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolAttemptAuditOutboxDto.forAttempt(
                        VALID_UUID_1,
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED,
                        "{}",
                        now));

        // forResolution with attempt event rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolAttemptAuditOutboxDto.forResolution(
                        VALID_UUID_1,
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                        "{}",
                        now));

        // Attempt event with non-null resolutionId rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAuditOutboxDto(
                        VALID_UUID_1,
                        "key-1",
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        VALID_UUID_3,
                        ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                        "{}",
                        now,
                        0,
                        now,
                        null,
                        null,
                        null,
                        null));

        // Resolution event with non-null attemptId rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAuditOutboxDto(
                        VALID_UUID_1,
                        "key-1",
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        VALID_UUID_3,
                        ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED,
                        "{}",
                        now,
                        0,
                        now,
                        null,
                        null,
                        null,
                        null));

        // deliveredAt set without deliveredAuditEventId rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAuditOutboxDto(
                        VALID_UUID_1,
                        "key-1",
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        null,
                        ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                        "{}",
                        now,
                        1,
                        now,
                        "lease-1",
                        now,
                        now,
                        null));
    }

    // =========================================================================
    // 8. ToolAttemptResolutionDto Invariant Tests (§12.4, §12.7)
    // =========================================================================

    @Test
    void testOutboxLeaseAndDeliveryFieldsArePaired() {
        Instant now = Instant.now();
        ToolAttemptAuditOutboxDto pending = ToolAttemptAuditOutboxDto.forAttempt(
                VALID_UUID_1, "ws-1", "sess-1", VALID_UUID_2, ToolAttemptAuditEventKind.ATTEMPT_ADMITTED, "{}", now);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAuditOutboxDto(
                        pending.eventId(),
                        pending.idempotencyKey(),
                        pending.workspaceId(),
                        pending.sessionId(),
                        pending.attemptId(),
                        null,
                        pending.eventKind(),
                        pending.safeMetadataJson(),
                        pending.createdAt(),
                        0,
                        now,
                        "lease-1",
                        null,
                        null,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAuditOutboxDto(
                        pending.eventId(),
                        pending.idempotencyKey(),
                        pending.workspaceId(),
                        pending.sessionId(),
                        pending.attemptId(),
                        null,
                        pending.eventKind(),
                        pending.safeMetadataJson(),
                        pending.createdAt(),
                        0,
                        now,
                        null,
                        now,
                        null,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAuditOutboxDto(
                        pending.eventId(),
                        pending.idempotencyKey(),
                        pending.workspaceId(),
                        pending.sessionId(),
                        pending.attemptId(),
                        null,
                        pending.eventKind(),
                        pending.safeMetadataJson(),
                        pending.createdAt(),
                        1,
                        now,
                        null,
                        null,
                        null,
                        VALID_UUID_4));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAuditOutboxDto(
                        pending.eventId(),
                        pending.idempotencyKey(),
                        pending.workspaceId(),
                        pending.sessionId(),
                        pending.attemptId(),
                        null,
                        pending.eventKind(),
                        pending.safeMetadataJson(),
                        pending.createdAt(),
                        1,
                        now,
                        null,
                        null,
                        now,
                        "not-a-uuid"));

        ToolAttemptAuditOutboxDto delivered = new ToolAttemptAuditOutboxDto(
                pending.eventId(),
                pending.idempotencyKey(),
                pending.workspaceId(),
                pending.sessionId(),
                pending.attemptId(),
                null,
                pending.eventKind(),
                pending.safeMetadataJson(),
                pending.createdAt(),
                1,
                now,
                null,
                null,
                now,
                VALID_UUID_4);
        assertEquals(VALID_UUID_4, delivered.deliveredAuditEventId());

        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolAttemptAuditOutboxDto(
                        pending.eventId(),
                        pending.idempotencyKey(),
                        pending.workspaceId(),
                        pending.sessionId(),
                        pending.attemptId(),
                        null,
                        pending.eventKind(),
                        pending.safeMetadataJson(),
                        pending.createdAt(),
                        1,
                        now,
                        "lease-1",
                        now,
                        now,
                        VALID_UUID_4));

        assertThrows(
                NullPointerException.class,
                () -> ToolAttemptAuditOutboxDto.forAttempt(
                        VALID_UUID_1,
                        "ws-1",
                        "sess-1",
                        VALID_UUID_2,
                        ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                        null,
                        now));
    }

    @Test
    void testAuditChainFinalizerFunctionalContract() {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        AuditEventDto seed = new AuditEventDto(
                eventId,
                "sess-1",
                "user-1",
                "agent",
                "goal",
                "SUCCESS",
                "TOOL_ATTEMPT_ADMITTED",
                "{}",
                0,
                0,
                null,
                now,
                1,
                "1.0",
                "idemp-1",
                0L,
                null,
                null);

        AuditChainFinalizer finalizer = (s, seq, prevHash) -> new AuditEventDto(
                s.id(),
                s.sessionId(),
                s.userId(),
                s.agentRole(),
                s.goalHash(),
                s.outcome(),
                s.eventType(),
                s.eventDetail(),
                s.inputTokens(),
                s.outputTokens(),
                s.usdCost(),
                s.timestamp(),
                s.iterationsUsed(),
                s.appVersion(),
                s.idempotencyKey(),
                seq,
                "computed-hmac",
                prevHash);

        AuditEventDto finalized = finalizer.finalizeChain(seed, 42L, "hash-41");
        assertEquals(42L, finalized.sequence());
        assertEquals("hash-41", finalized.prevHash());
        assertEquals("computed-hmac", finalized.hmacHash());
    }

    @Test
    void testAuditEventStorePortDefaultAppendChainedThrows() {
        AuditEventStorePort port = new AuditEventStorePort() {
            @Override
            public void save(AuditEventDto dto) {}

            @Override
            public Optional<String> findLatestHash(String sessionId) {
                return Optional.empty();
            }

            @Override
            public List<AuditEventDto> findBySessionId(String sessionId) {
                return List.of();
            }

            @Override
            public long countByIdempotencyKey(String key) {
                return 0;
            }

            @Override
            public long countEventsByType(String sessionId, String eventType) {
                return 0;
            }

            @Override
            public long getMaxSequence(String sessionId) {
                return 0;
            }

            @Override
            public Optional<AuditEventDto> findById(UUID id) {
                return Optional.empty();
            }

            @Override
            public List<AuditEventDto> findAll(
                    String sessionId, String eventType, Instant from, Instant to, int offset, int limit) {
                return List.of();
            }

            @Override
            public long countAll(String sessionId, String eventType, Instant from, Instant to) {
                return 0;
            }
        };

        Instant now = Instant.now();
        AuditEventDto seed = new AuditEventDto(
                UUID.randomUUID(),
                "sess-1",
                "user-1",
                "agent",
                "goal",
                "SUCCESS",
                "TOOL_ATTEMPT_ADMITTED",
                "{}",
                0,
                0,
                null,
                now,
                1,
                "1.0",
                "idemp-1",
                0L,
                null,
                null);

        assertThrows(UnsupportedOperationException.class, () -> port.appendChained(seed, (s, _, _) -> s));
    }
}
