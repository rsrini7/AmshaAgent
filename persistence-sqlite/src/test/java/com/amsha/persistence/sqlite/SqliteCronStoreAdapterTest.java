package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.common.util.TemporalUtils;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteCronStoreAdapterTest {

    @Inject
    SqliteCronStoreAdapter adapter;

    @Inject
    Jdbi jdbi;

    @Test
    void testSaveFindAndLifecycle() {
        String id = UUID.randomUUID().toString();
        String userId = "user-" + UUID.randomUUID();
        Instant now = Instant.now();
        AgentCronDto dto = new AgentCronDto(
                id,
                "session-1",
                userId,
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

        List<AgentCronDto> byUser = adapter.findByUserId(userId);
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

        int deleted = adapter.deleteByIdAndUserId(id, userId);
        assertEquals(1, deleted);
        assertTrue(adapter.findById(id).isEmpty());
    }

    @Test
    void testReclaimStaleUsesUpdatedAtNotCreatedAt() {
        String id = UUID.randomUUID().toString();
        Instant tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant oneMinuteAgo = Instant.now().minus(1, ChronoUnit.MINUTES);

        // Created 10m ago, but updated/claimed 1m ago in RUNNING state
        AgentCronDto activeRunning = new AgentCronDto(
                id,
                "session-active",
                "user-reclaim",
                "chat-1",
                "CLI",
                "platform-1",
                "0 * * * *",
                "UTC",
                true,
                "Goal Active",
                tenMinutesAgo,
                null,
                CronStatus.RUNNING.name(),
                "coordinator",
                null,
                null,
                tenMinutesAgo,
                oneMinuteAgo);

        adapter.save(activeRunning);

        // Threshold is 5m ago. Job running for 1m should NOT be reclaimed despite createdAt being 10m ago
        adapter.reclaimStaleCrons(Instant.now().minus(5, ChronoUnit.MINUTES));
        Optional<AgentCronDto> afterFirstCheck = adapter.findById(id);
        assertTrue(afterFirstCheck.isPresent());
        assertEquals(CronStatus.RUNNING.name(), afterFirstCheck.get().status());

        // Now simulate job stuck running for 10m by modifying updatedAt in DB (simulating passage of time)
        jdbi.useHandle(h -> h.createUpdate("UPDATE agent_cron SET updatedAt = :ts WHERE id = :id")
                .bind("ts", TemporalUtils.formatIsoLex(tenMinutesAgo))
                .bind("id", id)
                .execute());

        adapter.reclaimStaleCrons(Instant.now().minus(5, ChronoUnit.MINUTES));
        Optional<AgentCronDto> afterSecondCheck = adapter.findById(id);
        assertTrue(afterSecondCheck.isPresent());
        assertEquals(CronStatus.ACTIVE.name(), afterSecondCheck.get().status());

        adapter.deleteByIdAndUserId(id, "user-reclaim");
    }

    @Test
    void testReclaimStaleFallbackOnNullUpdatedAt() {
        String id = UUID.randomUUID().toString();
        String userId = "user-null-" + UUID.randomUUID();
        Instant tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES);

        AgentCronDto legacyRow = new AgentCronDto(
                id,
                "session-legacy",
                userId,
                "chat-1",
                "CLI",
                "platform-1",
                "0 * * * *",
                "UTC",
                true,
                "Legacy Goal",
                tenMinutesAgo,
                null,
                CronStatus.RUNNING.name(),
                "coordinator",
                null,
                null,
                tenMinutesAgo,
                null);

        adapter.save(legacyRow);

        // Force updatedAt to NULL directly in DB to simulate legacy row
        jdbi.useHandle(
                h -> h.createUpdate("UPDATE agent_cron SET updatedAt = NULL, createdAt = :created WHERE id = :id")
                        .bind("created", TemporalUtils.formatIsoLex(tenMinutesAgo))
                        .bind("id", id)
                        .execute());

        // Reclaim threshold is 5m ago. Because createdAt is 10m ago, NULL updatedAt row MUST be reclaimed
        adapter.reclaimStaleCrons(Instant.now().minus(5, ChronoUnit.MINUTES));
        Optional<AgentCronDto> reclaimed = adapter.findById(id);
        assertTrue(reclaimed.isPresent());
        assertEquals(CronStatus.ACTIVE.name(), reclaimed.get().status());
        assertNotNull(reclaimed.get().updatedAt());

        adapter.deleteByIdAndUserId(id, userId);
    }

    @Test
    void testRenewLease() {
        String id = UUID.randomUUID().toString();
        String userId = "user-lease-" + UUID.randomUUID();
        Instant now = Instant.now();

        AgentCronDto job = new AgentCronDto(
                id,
                "session-lease",
                userId,
                "chat-1",
                "CLI",
                "platform-1",
                "0 * * * *",
                "UTC",
                true,
                "Lease Goal",
                now,
                null,
                CronStatus.RUNNING.name(),
                "coordinator",
                null,
                "delivery-123",
                now,
                now);

        adapter.save(job);

        // Should renew with matching deliveryId
        assertTrue(adapter.renewLease(id, "delivery-123"));

        // Should fail to renew with wrong deliveryId
        assertFalse(adapter.renewLease(id, "wrong-delivery"));

        // When status is not RUNNING, renewal fails
        adapter.markCompleted(id, Instant.now());
        assertFalse(adapter.renewLease(id, "delivery-123"));

        adapter.deleteByIdAndUserId(id, userId);
    }

    @Test
    void testAtomicOwnershipTransitions() {
        String id = UUID.randomUUID().toString();
        String userId = "user-atomic-" + UUID.randomUUID();
        Instant now = Instant.now();

        AgentCronDto job = new AgentCronDto(
                id,
                "session-atomic",
                userId,
                "chat-1",
                "CLI",
                "platform-1",
                "0 * * * *",
                "UTC",
                true,
                "Atomic Goal",
                now,
                null,
                CronStatus.RUNNING.name(),
                "coordinator",
                null,
                "delivery-owner",
                now,
                now);

        adapter.save(job);

        // Transition with wrong deliveryId fails and does not change state
        assertFalse(adapter.markCompletedIfOwner(id, "delivery-stolen", Instant.now()));
        Optional<AgentCronDto> unchanged = adapter.findById(id);
        assertTrue(unchanged.isPresent());
        assertEquals(CronStatus.RUNNING.name(), unchanged.get().status());

        // Transition with matching deliveryId succeeds
        assertTrue(adapter.markCompletedIfOwner(id, "delivery-owner", Instant.now()));
        Optional<AgentCronDto> completed = adapter.findById(id);
        assertTrue(completed.isPresent());
        assertEquals(CronStatus.COMPLETED.name(), completed.get().status());

        adapter.deleteByIdAndUserId(id, userId);
    }
}
