package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.AuditEventStorePort;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class AuditIdempotencyJdbiTest {

    @Inject
    AuditEventStorePort auditStore;

    @Test
    void uuidRoundTrip() {
        UUID originalId = UUID.randomUUID();
        AuditEventDto dto = new AuditEventDto(
                originalId,
                "session-roundtrip",
                "user-1",
                "coordinator",
                "hash1",
                "completed",
                "test",
                "detail",
                10,
                20,
                BigDecimal.valueOf(0.001),
                Instant.now(),
                1,
                "1.0",
                "key1",
                1L,
                "hmac1",
                "prev1");
        auditStore.save(dto);

        AuditEventDto fetched = auditStore.findById(originalId).orElse(null);
        assertNotNull(fetched, "Should find audit event by UUID");
        assertEquals(originalId, fetched.id(), "UUID should round-trip correctly through BLOB column");
    }

    @Test
    void idempotencyKeyIsStoredAndCounted() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String idempotencyKey = "idem-" + UUID.randomUUID().toString().substring(0, 8);

        AuditEventDto first = new AuditEventDto(
                id1,
                "session-idem",
                "user-1",
                "coordinator",
                "hash1",
                "completed",
                "test",
                "detail",
                10,
                20,
                BigDecimal.valueOf(0.001),
                Instant.now(),
                1,
                "1.0",
                idempotencyKey,
                1L,
                "hmac1",
                "prev1");
        auditStore.save(first);

        // Save event with same idempotency key (application-level dedup,
        // no DB unique constraint, so both rows can exist)
        AuditEventDto second = new AuditEventDto(
                id2,
                "session-idem",
                "user-1",
                "coordinator",
                "hash1",
                "completed",
                "test",
                "detail",
                10,
                20,
                BigDecimal.valueOf(0.001),
                Instant.now(),
                1,
                "1.0",
                idempotencyKey,
                1L,
                "hmac2",
                "prev2");
        auditStore.save(second);

        // Both rows stored with the same key
        long count = auditStore.countByIdempotencyKey(idempotencyKey);
        assertEquals(2, count, "Both events share the same idempotency key");
    }

    @Test
    void findByIdReturnsCorrectEvent() {
        UUID id = UUID.randomUUID();
        AuditEventDto dto = new AuditEventDto(
                id,
                "session-findbyid",
                "user-1",
                "coordinator",
                "hash1",
                "completed",
                "test",
                "detail",
                10,
                20,
                BigDecimal.valueOf(0.001),
                Instant.now(),
                1,
                "1.0",
                "key-find-" + id,
                1L,
                "hmac1",
                "prev1");
        auditStore.save(dto);

        AuditEventDto fetched = auditStore.findById(id).orElse(null);
        assertNotNull(fetched);
        assertEquals(id, fetched.id());
        assertEquals("session-findbyid", fetched.sessionId());
        assertEquals("coordinator", fetched.agentRole());
        assertEquals("hmac1", fetched.hmacHash());
        assertEquals(1L, fetched.sequence());
    }

    @Test
    void findByIdReturnsEmptyForNonExistent() {
        UUID nonExistent = UUID.randomUUID();
        assertTrue(auditStore.findById(nonExistent).isEmpty());
    }
}
