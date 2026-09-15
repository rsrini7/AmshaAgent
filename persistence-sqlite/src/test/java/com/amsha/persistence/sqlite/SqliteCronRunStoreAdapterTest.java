package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteCronRunStoreAdapterTest {

    @Inject
    SqliteCronRunStoreAdapter store;

    @Test
    void testCronRunStoreOperations() {
        String cronId = "cron-" + UUID.randomUUID();
        String runId = "run-" + UUID.randomUUID();

        CronRunDto dto = new CronRunDto(
                runId,
                cronId,
                "session-123",
                "SUCCESS",
                Instant.now(),
                Instant.now().plusMillis(500),
                null,
                1,
                100,
                50,
                BigDecimal.valueOf(0.01),
                Instant.now());

        store.save(dto);

        Optional<CronRunDto> byId = store.findById(runId);
        assertTrue(byId.isPresent());
        assertEquals("SUCCESS", byId.get().status());

        List<CronRunDto> byCronId = store.findByCronId(cronId, 0, 10);
        assertEquals(1, byCronId.size());

        assertEquals(1, store.countByCronId(cronId));

        List<CronRunDto> allSuccess = store.findAll("SUCCESS", 0, 10);
        assertFalse(allSuccess.isEmpty());

        assertTrue(store.countAll("SUCCESS") >= 1);
        assertTrue(store.countAll(null) >= 1);

        // Blank/null check safety
        assertTrue(store.findById(null).isEmpty());
    }

    @Test
    void findAllWithoutAStatusFilterUsesTheUnfilteredQuery() {
        String cronId = "cron-all-" + UUID.randomUUID();
        String runId = "run-all-" + UUID.randomUUID();
        store.save(new CronRunDto(
                runId,
                cronId,
                "session-all",
                "FAILED",
                Instant.now(),
                Instant.now().plusMillis(10),
                "boom",
                1,
                1,
                1,
                BigDecimal.ZERO,
                Instant.now()));

        List<CronRunDto> unfilteredNull = store.findAll(null, 0, 10);
        List<CronRunDto> unfilteredBlank = store.findAll("   ", 0, 10);

        assertTrue(unfilteredNull.stream().anyMatch(r -> r.id().equals(runId)));
        assertTrue(
                unfilteredBlank.stream().anyMatch(r -> r.id().equals(runId)),
                "a blank status must be treated as no filter, not as a literal status value");
        assertEquals(unfilteredNull.size(), unfilteredBlank.size());
    }
}
