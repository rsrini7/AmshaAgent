package com.arcclaw.core.state;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link AgentCronRepository} using real in-memory SQLite.
 * <p>
 * Note: Tests that call {@code findDueCrons()} (native SQL) must NOT share
 * a transaction with the insert path. Use {@code repository.persist()} which
 * commits its own micro-transaction, then call {@code findDueCrons()} outside
 * any test-scoped transaction to avoid SQLite locking contention.
 */
@QuarkusTest
class AgentCronRepositoryTest {

    @Inject
    AgentCronRepository repository;

    @Inject
    EntityManager em;

    private AgentCronEntity createEntity(String userId, String status, Instant nextFireTime) {
        AgentCronEntity e = new AgentCronEntity();
        e.id = UUID.randomUUID().toString();
        e.sessionId = "session-" + e.id;
        e.userId = userId;
        e.chatId = "chat-1";
        e.cronExpression = "0 9 * * 1";
        e.timezone = "UTC";
        e.runOnce = false;
        e.goal = "CRON: Test goal";
        e.nextFireTime = nextFireTime;
        e.status = status;
        e.createdAt = Instant.now();
        return e;
    }

    // ──────────────────────────────────────────────────────────────
    // Tests using native SQL findDueCrons() — NO @Transactional
    // Uses repository.persist() which commits its own micro-transaction
    // ──────────────────────────────────────────────────────────────

    @Test
    void testFindDueCronsReturnsDueRows() {
        AgentCronEntity e = createEntity("user-due-1", "ACTIVE",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        repository.persist(e);

        List<AgentCronEntity> due = repository.findDueCrons();
        assertTrue(due.stream().anyMatch(d -> d.id.equals(e.id)),
                "Due cron should be returned");
    }

    @Test
    void testFindDueCronsExcludesFutureRows() {
        AgentCronEntity e = createEntity("user-due-2", "ACTIVE",
                Instant.now().plus(1, ChronoUnit.HOURS));
        repository.persist(e);

        List<AgentCronEntity> due = repository.findDueCrons();
        assertFalse(due.stream().anyMatch(d -> d.id.equals(e.id)),
                "Future cron should NOT be returned");
    }

    @Test
    void testFindDueCronsExcludesNonActiveRows() {
        AgentCronEntity e = createEntity("user-due-3", "COMPLETED",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        repository.persist(e);

        List<AgentCronEntity> due = repository.findDueCrons();
        assertFalse(due.stream().anyMatch(d -> d.id.equals(e.id)),
                "COMPLETED cron should NOT be returned by findDueCrons");
    }

    @Test
    void testMarkCompletedSetsTerminalState() {
        AgentCronEntity e = createEntity("user-complete-1", "RUNNING",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        repository.persist(e);

        repository.markCompleted(e.id, Instant.now());

        // findDueCrons should not return COMPLETED rows
        List<AgentCronEntity> due = repository.findDueCrons();
        assertFalse(due.stream().anyMatch(d -> d.id.equals(e.id)));
    }

    // ──────────────────────────────────────────────────────────────
    // Tests using only JPQL/EntityManager — CAN use @Transactional
    // ──────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void testClaimForFireIsIdempotent() {
        AgentCronEntity e = createEntity("user-claim-1", "ACTIVE",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        em.persist(e);
        em.flush();
        em.clear();

        boolean first = repository.claimForFire(e.id);
        assertTrue(first, "First claim should succeed");

        boolean second = repository.claimForFire(e.id);
        assertFalse(second, "Second claim should fail (already RUNNING)");
    }

    @Test
    @Transactional
    void testMarkFiredUpdatesNextFireTime() {
        AgentCronEntity e = createEntity("user-fire-1", "RUNNING",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        em.persist(e);
        em.flush();
        em.clear();

        Instant newNext = Instant.now().plus(7, ChronoUnit.DAYS);
        repository.markFired(e.id, Instant.now(), newNext);
        em.flush();
        em.clear();

        AgentCronEntity updated = em.find(AgentCronEntity.class, e.id);
        assertEquals("ACTIVE", updated.status);
        assertNotNull(updated.lastFireTime);
        assertNotNull(updated.nextFireTime);
    }

    @Test
    @Transactional
    void testMarkFailedRecordsReason() {
        AgentCronEntity e = createEntity("user-fail-1", "RUNNING",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        em.persist(e);
        em.flush();
        em.clear();

        repository.markFailed(e.id, "Test failure reason");
        em.flush();
        em.clear();

        AgentCronEntity updated = em.find(AgentCronEntity.class, e.id);
        assertEquals("FAILED", updated.status);
        assertEquals("Test failure reason", updated.errorReason);
    }

    @Test
    @Transactional
    void testReleaseClaimToActiveResetsRunningOnly() {
        AgentCronEntity running = createEntity("user-release-1", "RUNNING",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        em.persist(running);

        AgentCronEntity failed = createEntity("user-release-1", "FAILED",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        em.persist(failed);
        em.flush();
        em.clear();

        repository.releaseClaimToActive(running.id);
        repository.releaseClaimToActive(failed.id);
        em.flush();
        em.clear();

        AgentCronEntity updatedRunning = em.find(AgentCronEntity.class, running.id);
        assertEquals("ACTIVE", updatedRunning.status, "RUNNING should become ACTIVE");

        AgentCronEntity updatedFailed = em.find(AgentCronEntity.class, failed.id);
        assertEquals("FAILED", updatedFailed.status, "FAILED should remain unchanged");
    }

    @Test
    @Transactional
    void testReclaimStaleCronsResetsRunning() {
        AgentCronEntity stale = createEntity("user-stale-1", "RUNNING",
                Instant.now().minus(10, ChronoUnit.MINUTES));
        stale.lastFireTime = Instant.now().minus(10, ChronoUnit.MINUTES);
        em.persist(stale);
        em.flush();
        em.clear();

        repository.reclaimStaleCrons(Instant.now().minus(5, ChronoUnit.MINUTES));
        em.flush();
        em.clear();

        AgentCronEntity updated = em.find(AgentCronEntity.class, stale.id);
        assertEquals("ACTIVE", updated.status, "Stale RUNNING job should be reclaimed to ACTIVE");
    }

    @Test
    @Transactional
    void testSetDeliveryIdPersists() {
        AgentCronEntity e = createEntity("user-delivery-1", "RUNNING",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        em.persist(e);
        em.flush();
        em.clear();

        String deliveryId = UUID.randomUUID().toString();
        repository.setDeliveryId(e.id, deliveryId);
        em.flush();
        em.clear();

        AgentCronEntity updated = em.find(AgentCronEntity.class, e.id);
        assertEquals(deliveryId, updated.lastDeliveryId);
    }

    @Test
    @Transactional
    void testDeleteByIdAndUserIdEnforcesTenantIsolation() {
        AgentCronEntity e = createEntity("user-tenant-A", "ACTIVE",
                Instant.now().plus(1, ChronoUnit.HOURS));
        em.persist(e);
        em.flush();
        em.clear();

        // User B tries to delete User A's job — should have no effect
        repository.deleteByIdAndUserId(e.id, "user-tenant-B");
        em.flush();
        em.clear();

        AgentCronEntity stillExists = em.find(AgentCronEntity.class, e.id);
        assertNotNull(stillExists, "User B should NOT be able to delete User A's cron");

        // User A deletes their own job — should succeed
        repository.deleteByIdAndUserId(e.id, "user-tenant-A");
        em.flush();
        em.clear();

        AgentCronEntity deleted = em.find(AgentCronEntity.class, e.id);
        assertNull(deleted, "User A should be able to delete their own cron");
    }

    @Test
    @Transactional
    void testReclaimStaleCronsHandlesNullLastFireTime() {
        AgentCronEntity stale = createEntity("user-stale-null", "RUNNING", 
                Instant.now().plus(1, ChronoUnit.HOURS));
        stale.lastFireTime = null; // Claimed but JVM crashed before AgentLoop started
        stale.createdAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        em.persist(stale);
        em.flush();
        em.clear();

        repository.reclaimStaleCrons(Instant.now().minus(5, ChronoUnit.MINUTES));
        em.flush();
        em.clear();

        AgentCronEntity updated = em.find(AgentCronEntity.class, stale.id);
        assertEquals("ACTIVE", updated.status, 
                "RUNNING job with null lastFireTime should be reclaimed based on createdAt");
    }

    @Test
    @Transactional
    void testDeleteAllByUserId() {
        // Arrange: Create jobs for two different users
        String targetUserId = "user-delete-target";
        String otherUserId = "user-delete-other";

        AgentCronEntity job1 = createEntity(targetUserId, "ACTIVE", Instant.now().plusSeconds(3600));
        AgentCronEntity job2 = createEntity(targetUserId, "COMPLETED", Instant.now().minusSeconds(3600));
        AgentCronEntity job3 = createEntity(otherUserId, "ACTIVE", Instant.now().plusSeconds(3600));

        em.persist(job1);
        em.persist(job2);
        em.persist(job3);
        em.flush();
        em.clear(); // Clear persistence context so subsequent queries hit the DB

        // Act: Delete all jobs for the target user
        long deletedCount = repository.deleteAllByUserId(targetUserId);
        em.flush();
        em.clear();

        // Assert: Count should exactly match the number of jobs target user had
        assertEquals(2, deletedCount, "Should have deleted exactly 2 jobs for the target user");

        // Assert: Verify target user's jobs are actually gone from the DB
        List<AgentCronEntity> targetJobs = repository.findByUserId(targetUserId);
        assertTrue(targetJobs.isEmpty(), "Target user should have 0 jobs remaining");

        // Assert: Verify tenant isolation (other user's jobs are untouched)
        List<AgentCronEntity> otherJobs = repository.findByUserId(otherUserId);
        assertEquals(1, otherJobs.size(), "Other user's jobs must remain intact");
        assertEquals(job3.id, otherJobs.get(0).id);
    }
}
