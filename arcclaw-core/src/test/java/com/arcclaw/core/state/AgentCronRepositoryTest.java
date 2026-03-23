package com.arcclaw.core.state;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

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

    private AgentCronEntity createEntity(String userId, CronStatus status, Instant nextFireTime) {
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
        AgentCronEntity e = createEntity("user-due-1", CronStatus.ACTIVE,
                Instant.now().minus(1, ChronoUnit.MINUTES));
        repository.persist(e);

        List<AgentCronEntity> due = repository.findDueCrons();
        assertTrue(due.stream().anyMatch(d -> d.id.equals(e.id)),
                "Due cron should be returned");
    }

    @Test
    void testFindDueCronsExcludesFutureRows() {
        AgentCronEntity e = createEntity("user-due-2", CronStatus.ACTIVE,
                Instant.now().plus(1, ChronoUnit.HOURS));
        repository.persist(e);

        List<AgentCronEntity> due = repository.findDueCrons();
        assertFalse(due.stream().anyMatch(d -> d.id.equals(e.id)),
                "Future cron should NOT be returned");
    }

    @Test
    void testFindDueCronsExcludesNonActiveRows() {
        AgentCronEntity e = createEntity("user-due-3", CronStatus.COMPLETED,
                Instant.now().minus(1, ChronoUnit.MINUTES));
        repository.persist(e);

        List<AgentCronEntity> due = repository.findDueCrons();
        assertFalse(due.stream().anyMatch(d -> d.id.equals(e.id)),
                "COMPLETED cron should NOT be returned by findDueCrons");
    }

    @Test
    void testMarkCompletedSetsTerminalState() {
        AgentCronEntity e = createEntity("user-complete-1", CronStatus.RUNNING,
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
        AgentCronEntity e = createEntity("user-claim-1", CronStatus.ACTIVE,
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
        AgentCronEntity e = createEntity("user-fire-1", CronStatus.RUNNING,
                Instant.now().minus(1, ChronoUnit.MINUTES));
        em.persist(e);
        em.flush();
        em.clear();

        Instant newNext = Instant.now().plus(7, ChronoUnit.DAYS);
        repository.markFired(e.id, Instant.now(), newNext);
        em.flush();
        em.clear();

        AgentCronEntity updated = em.find(AgentCronEntity.class, e.id);
        assertEquals(CronStatus.ACTIVE, updated.status);
        assertNotNull(updated.lastFireTime);
        assertNotNull(updated.nextFireTime);
    }

    @Test
    @Transactional
    void testMarkFailedRecordsReason() {
        AgentCronEntity e = createEntity("user-fail-1", CronStatus.RUNNING,
                Instant.now().minus(1, ChronoUnit.MINUTES));
        em.persist(e);
        em.flush();
        em.clear();

        repository.markFailed(e.id, "Test failure reason");
        em.flush();
        em.clear();

        AgentCronEntity updated = em.find(AgentCronEntity.class, e.id);
        assertEquals(CronStatus.FAILED, updated.status);
        assertEquals("Test failure reason", updated.errorReason);
    }

    @Test
    @Transactional
    void testReleaseClaimToActiveResetsRunningOnly() {
        AgentCronEntity running = createEntity("user-release-1", CronStatus.RUNNING,
                Instant.now().minus(1, ChronoUnit.MINUTES));
        em.persist(running);

        AgentCronEntity failed = createEntity("user-release-1", CronStatus.FAILED,
                Instant.now().minus(1, ChronoUnit.MINUTES));
        em.persist(failed);
        em.flush();
        em.clear();

        repository.releaseClaimToActive(running.id);
        repository.releaseClaimToActive(failed.id);
        em.flush();
        em.clear();

        AgentCronEntity updatedRunning = em.find(AgentCronEntity.class, running.id);
        assertEquals(CronStatus.ACTIVE, updatedRunning.status, "RUNNING should become ACTIVE");

        AgentCronEntity updatedFailed = em.find(AgentCronEntity.class, failed.id);
        assertEquals(CronStatus.FAILED, updatedFailed.status, "FAILED should remain unchanged");
    }

    @Test
    @Transactional
    void testReclaimStaleCronsResetsRunning() {
        AgentCronEntity stale = createEntity("user-stale-1", CronStatus.RUNNING,
                Instant.now().minus(10, ChronoUnit.MINUTES));
        stale.lastFireTime = Instant.now().minus(10, ChronoUnit.MINUTES);
        em.persist(stale);
        em.flush();
        em.clear();

        repository.reclaimStaleCrons(Instant.now().minus(5, ChronoUnit.MINUTES));
        em.flush();
        em.clear();

        AgentCronEntity updated = em.find(AgentCronEntity.class, stale.id);
        assertEquals(CronStatus.ACTIVE, updated.status, "Stale RUNNING job should be reclaimed to ACTIVE");
    }

    @Test
    @Transactional
    void testSetDeliveryIdPersists() {
        AgentCronEntity e = createEntity("user-delivery-1", CronStatus.RUNNING,
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
        AgentCronEntity e = createEntity("user-tenant-A", CronStatus.ACTIVE,
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
        AgentCronEntity stale = createEntity("user-stale-null", CronStatus.RUNNING, 
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
        assertEquals(CronStatus.ACTIVE, updated.status, 
                "RUNNING job with null lastFireTime should be reclaimed based on createdAt");
    }

    @Test
    @Transactional
    void testDeleteAllByUserId() {
        // Arrange: Create jobs for two different users
        String targetUserId = "user-delete-target";
        String otherUserId = "user-delete-other";

        AgentCronEntity job1 = createEntity(targetUserId, CronStatus.ACTIVE, Instant.now().plusSeconds(3600));
        AgentCronEntity job2 = createEntity(targetUserId, CronStatus.COMPLETED, Instant.now().minusSeconds(3600));
        AgentCronEntity job3 = createEntity(otherUserId, CronStatus.ACTIVE, Instant.now().plusSeconds(3600));

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

    @Test
    @Transactional
    void testReclaimStaleCronsDoesNotReclaimFailedOrCompletedRows() {
        Instant threshold = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant oldTime   = Instant.now().minus(20, ChronoUnit.MINUTES); // older than threshold

        // Row 1: RUNNING + null lastFireTime + old createdAt → MUST be reclaimed
        AgentCronEntity stuckRunning = new AgentCronEntity();
        stuckRunning.id             = UUID.randomUUID().toString();
        stuckRunning.userId         = "user-1";
        stuckRunning.chatId         = "chat-1";
        stuckRunning.sessionId      = "session-1";
        stuckRunning.goal           = "CRON: test";
        stuckRunning.cronExpression = "0 9 * * *";
        stuckRunning.timezone       = "UTC";
        stuckRunning.status         = CronStatus.RUNNING;
        stuckRunning.lastFireTime   = null;
        stuckRunning.createdAt      = oldTime;
        stuckRunning.nextFireTime   = oldTime;
        repository.persist(stuckRunning);

        // Row 2: FAILED + null lastFireTime + old createdAt → MUST NOT be reclaimed
        AgentCronEntity failedRow = new AgentCronEntity();
        failedRow.id             = UUID.randomUUID().toString();
        failedRow.userId         = "user-1";
        failedRow.chatId         = "chat-1";
        failedRow.sessionId      = "session-2";
        failedRow.goal           = "CRON: test";
        failedRow.cronExpression = "0 9 * * *";
        failedRow.timezone       = "UTC";
        failedRow.status         = CronStatus.FAILED;
        failedRow.lastFireTime   = null;
        failedRow.createdAt      = oldTime;
        failedRow.nextFireTime   = oldTime;
        repository.persist(failedRow);

        // Row 3: COMPLETED + null lastFireTime + old createdAt → MUST NOT be reclaimed
        AgentCronEntity completedRow = new AgentCronEntity();
        completedRow.id             = UUID.randomUUID().toString();
        completedRow.userId         = "user-1";
        completedRow.chatId         = "chat-1";
        completedRow.sessionId      = "session-3";
        completedRow.goal           = "CRON: test";
        completedRow.cronExpression = "0 9 * * *";
        completedRow.timezone       = "UTC";
        completedRow.status         = CronStatus.COMPLETED;
        completedRow.lastFireTime   = null;
        completedRow.createdAt      = oldTime;
        completedRow.nextFireTime   = oldTime;
        repository.persist(completedRow);

        // Row 4: RUNNING + stale lastFireTime (non-null) → MUST be reclaimed
        AgentCronEntity stuckRunning2 = new AgentCronEntity();
        stuckRunning2.id             = UUID.randomUUID().toString();
        stuckRunning2.userId         = "user-1";
        stuckRunning2.chatId         = "chat-1";
        stuckRunning2.sessionId      = "session-4";
        stuckRunning2.goal           = "CRON: test";
        stuckRunning2.cronExpression = "0 9 * * *";
        stuckRunning2.timezone       = "UTC";
        stuckRunning2.status         = CronStatus.RUNNING;
        stuckRunning2.lastFireTime   = oldTime; // stale non-null
        stuckRunning2.createdAt      = oldTime;
        stuckRunning2.nextFireTime   = oldTime;
        repository.persist(stuckRunning2);

        // Row 5: RUNNING + recent lastFireTime → MUST NOT be reclaimed (still executing)
        AgentCronEntity activeRunning = new AgentCronEntity();
        activeRunning.id             = UUID.randomUUID().toString();
        activeRunning.userId         = "user-1";
        activeRunning.chatId         = "chat-1";
        activeRunning.sessionId      = "session-5";
        activeRunning.goal           = "CRON: test";
        activeRunning.cronExpression = "0 9 * * *";
        activeRunning.timezone       = "UTC";
        activeRunning.status         = CronStatus.RUNNING;
        activeRunning.lastFireTime   = Instant.now().minus(1, ChronoUnit.MINUTES); // recent
        activeRunning.createdAt      = oldTime;
        activeRunning.nextFireTime   = oldTime;
        repository.persist(activeRunning);

        em.flush();
        em.clear();

        // Act
        repository.reclaimStaleCrons(threshold);

        em.flush();
        em.clear();

        // Assert — reload all rows fresh
        AgentCronEntity r1 = findById(stuckRunning.id);
        AgentCronEntity r2 = findById(failedRow.id);
        AgentCronEntity r3 = findById(completedRow.id);
        AgentCronEntity r4 = findById(stuckRunning2.id);
        AgentCronEntity r5 = findById(activeRunning.id);

        // Row 1: RUNNING + null createdAt old → must be ACTIVE now
        assertEquals(CronStatus.ACTIVE, r1.status,
                "RUNNING row with null lastFireTime older than threshold must be reclaimed to ACTIVE");

        // Row 2: FAILED must stay FAILED — the SQL bug would flip this to ACTIVE
        assertEquals(CronStatus.FAILED, r2.status,
                "FAILED row must NOT be reclaimed — SQL operator precedence bug if this fails");

        // Row 3: COMPLETED must stay COMPLETED
        assertEquals(CronStatus.COMPLETED, r3.status,
                "COMPLETED row must NOT be reclaimed — SQL operator precedence bug if this fails");

        // Row 4: RUNNING + stale non-null lastFireTime → must be ACTIVE
        assertEquals(CronStatus.ACTIVE, r4.status,
                "RUNNING row with stale lastFireTime must be reclaimed to ACTIVE");

        // Row 5: RUNNING + recent lastFireTime → must stay RUNNING
        assertEquals(CronStatus.RUNNING, r5.status,
                "RUNNING row with recent lastFireTime must NOT be reclaimed — still executing");
        }

        private AgentCronEntity findById(String id) {
                return (AgentCronEntity) em.createNativeQuery(
                        "SELECT * FROM agentcron WHERE id = :id", AgentCronEntity.class)
                        .setParameter("id", id)
                        .getSingleResult();
        }


}
