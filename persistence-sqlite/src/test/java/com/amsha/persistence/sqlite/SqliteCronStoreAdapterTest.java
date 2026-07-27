package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.state.CronStatus;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteCronStoreAdapterTest {

    @Inject
    SqliteCronStoreAdapter adapter;

    @Test
    void testSaveFindAndLifecycle() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AgentCronDto dto = new AgentCronDto(
                id,
                "session-1",
                "user-1",
                "chat-1",
                "CLI",
                "platform-1",
                "0 * * * *",
                "UTC",
                false,
                "Goal 1",
                now,
                null,
                CronStatus.ACTIVE.name(),
                "coordinator",
                null,
                null,
                now,
                now);

        adapter.save(dto);

        Optional<AgentCronDto> loaded = adapter.findById(id);
        assertTrue(loaded.isPresent());
        assertEquals("Goal 1", loaded.get().goal());

        List<AgentCronDto> byUser = adapter.findByUserId("user-1");
        assertEquals(1, byUser.size());

        boolean claimed = adapter.claimForFire(id);
        assertTrue(claimed);

        adapter.releaseClaimToActive(id);
        Optional<AgentCronDto> afterRelease = adapter.findById(id);
        assertEquals(CronStatus.ACTIVE.name(), afterRelease.get().status());

        adapter.markAwaitingApproval(id);
        List<AgentCronDto> awaiting = adapter.findAllAwaitingApproval();
        assertFalse(awaiting.isEmpty());

        adapter.markRunning(id);
        Optional<AgentCronDto> running = adapter.findById(id);
        assertEquals(CronStatus.RUNNING.name(), running.get().status());

        adapter.markFailed(id, "Error occurred");
        Optional<AgentCronDto> failed = adapter.findById(id);
        assertEquals(CronStatus.FAILED.name(), failed.get().status());
        assertEquals("Error occurred", failed.get().errorReason());

        int deleted = adapter.deleteByIdAndUserId(id, "user-1");
        assertEquals(1, deleted);
        assertTrue(adapter.findById(id).isEmpty());
    }
}
