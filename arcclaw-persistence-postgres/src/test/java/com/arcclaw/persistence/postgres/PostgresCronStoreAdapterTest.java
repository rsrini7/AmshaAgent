package com.arcclaw.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class PostgresCronStoreAdapterTest {

    @Inject
    CronStorePort store;

    @Test
    void testSaveAndFindById() {
        AgentCronDto cron = createCron("cron-save-1", "user-1", "ACTIVE");
        store.save(cron);
        Optional<AgentCronDto> found = store.findById("cron-save-1");
        assertTrue(found.isPresent());
        assertEquals("user-1", found.get().userId());
    }

    @Test
    void testClaimForFireAndRelease() {
        store.save(createCron("cron-claim-1", "user-1", "ACTIVE"));
        assertTrue(store.claimForFire("cron-claim-1"));
        Optional<AgentCronDto> claimed = store.findById("cron-claim-1");
        assertTrue(claimed.isPresent());
        assertEquals("RUNNING", claimed.get().status());
    }

    @Test
    void testClaimForFireFailsWhenWrongStatus() {
        store.save(createCron("cron-claim-2", "user-1", "COMPLETED"));
        assertFalse(store.claimForFire("cron-claim-2"));
    }

    @Test
    void testReleaseClaimToActive() {
        store.save(createCron("cron-release-1", "user-1", "ACTIVE"));
        store.claimForFire("cron-release-1");
        store.releaseClaimToActive("cron-release-1");
        assertEquals("ACTIVE", store.findById("cron-release-1").get().status());
    }

    @Test
    void testFindByUserId() {
        store.save(createCron("cron-user-1", "user-A", "ACTIVE"));
        store.save(createCron("cron-user-2", "user-A", "ACTIVE"));
        store.save(createCron("cron-user-3", "user-B", "ACTIVE"));
        List<AgentCronDto> userACrons = store.findByUserId("user-A");
        assertEquals(2, userACrons.size());
    }

    @Test
    void testMarkFired() {
        store.save(createCron("cron-fired-1", "user-1", "ACTIVE"));
        store.claimForFire("cron-fired-1");
        store.markFired("cron-fired-1", Instant.now(), Instant.now().plusSeconds(3600));
        Optional<AgentCronDto> fired = store.findById("cron-fired-1");
        assertTrue(fired.isPresent());
        assertEquals("ACTIVE", fired.get().status());
    }

    @Test
    void testMarkCompleted() {
        store.save(createCron("cron-complete-1", "user-1", "ACTIVE"));
        store.claimForFire("cron-complete-1");
        store.markCompleted("cron-complete-1", Instant.now());
        assertEquals("COMPLETED", store.findById("cron-complete-1").get().status());
    }

    @Test
    void testMarkFailed() {
        store.save(createCron("cron-fail-1", "user-1", "RUNNING"));
        store.markFailed("cron-fail-1", "error reason");
        assertEquals("FAILED", store.findById("cron-fail-1").get().status());
    }

    @Test
    void testFindAllAwaitingApproval() {
        store.save(createCron("cron-aa-1", "user-1", "AWAITING_APPROVAL"));
        store.save(createCron("cron-aa-2", "user-2", "AWAITING_APPROVAL"));
        store.save(createCron("cron-aa-3", "user-3", "ACTIVE"));
        List<AgentCronDto> awaiting = store.findAllAwaitingApproval();
        assertEquals(2, awaiting.size());
    }

    @Test
    void testSetDeliveryId() {
        store.save(createCron("cron-deliv-1", "user-1", "ACTIVE"));
        store.setDeliveryId("cron-deliv-1", "delivery-123");
        Optional<AgentCronDto> found = store.findById("cron-deliv-1");
        assertTrue(found.isPresent());
        assertEquals("delivery-123", found.get().lastDeliveryId());
    }

    @Test
    void testMarkSkipped() {
        store.save(createCron("cron-skip-1", "user-1", "RUNNING"));
        Instant next = Instant.now().plusSeconds(3600);
        store.markSkipped("cron-skip-1", next, Instant.now());
        Optional<AgentCronDto> found = store.findById("cron-skip-1");
        assertTrue(found.isPresent());
        assertNotNull(found.get().nextFireTime());
    }

    private AgentCronDto createCron(String id, String userId, String status) {
        return new AgentCronDto(id, "sess-" + id, userId, "chat-1", "telegram",
            "plat-1", "0 0 * * * ?", "UTC", false, "goal-" + id,
            Instant.now().plusSeconds(3600), null, status, "assistant", null, null,
            Instant.now(), Instant.now());
    }
}
