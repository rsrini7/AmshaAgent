package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.persistence.postgres.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@QuarkusTestResource(PostgresTestResource.class)
class PostgresCronRunStoreAdapterTest {

    @Inject
    CronRunStorePort store;

    @Inject
    CronStorePort cronStore;

    @Test
    void testCronRunStoreOperations() {
        String cronId = "cron-" + UUID.randomUUID();
        String runId = "run-" + UUID.randomUUID();

        cronStore.save(new AgentCronDto(
                cronId,
                "sess-" + cronId,
                "user-1",
                "chat-1",
                "telegram",
                "plat-1",
                "0 0 * * * ?",
                "UTC",
                false,
                "goal-" + cronId,
                Instant.now().plusSeconds(3600),
                null,
                "ACTIVE",
                "assistant",
                null,
                null,
                Instant.now(),
                Instant.now()));

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

        List<CronRunDto> allNullFilter = store.findAll(null, 0, 10);
        assertFalse(allNullFilter.isEmpty());

        long totalCount = store.countAll(null);
        assertTrue(totalCount > 0);

        // The status-filtered branch of countAll takes a different DAO query from the unfiltered one.
        // Asserted as a lower bound because the run table is shared across the module's test classes.
        assertTrue(store.countAll("SUCCESS") >= 1, "the filtered count must include the run just saved");
    }
}
