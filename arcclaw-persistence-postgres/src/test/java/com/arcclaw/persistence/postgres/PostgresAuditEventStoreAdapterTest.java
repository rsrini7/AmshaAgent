package com.arcclaw.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class PostgresAuditEventStoreAdapterTest {

    @Inject
    AuditEventStorePort store;

    @Test
    void testSaveAndFindFilteredWithNulls() {
        UUID id = UUID.randomUUID();
        AuditEventDto dto = new AuditEventDto(
                id,
                "session-123",
                "user-123",
                "coordinator",
                "goalhash",
                "SUCCESS",
                "TELEMETRY",
                "detail",
                10,
                20,
                new BigDecimal("0.001"),
                Instant.now(),
                1,
                "1.0.0",
                "idemp-key-123",
                1L,
                "hmac",
                "prev");

        store.save(dto);

        // Retrieve specifically using null filters (which previously threw Exception on postgres)
        List<AuditEventDto> results = store.findAll(null, null, null, null, 0, 10);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(e -> e.id().equals(id)));

        long count = store.countAll(null, null, null, null);
        assertTrue(count > 0);
    }
}
