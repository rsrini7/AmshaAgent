package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsCronStoreAdapterTest {

    @Mock
    FilesystemFileStore store;

    @Mock
    FsLockManager locks;

    @InjectMocks
    FsCronStoreAdapter adapter;

    private static final List<String> DOMAIN_BY_ID = List.of("crons", "by-id");

    private AgentCronDto createCron(String id, String status, Instant nextFireTime) {
        return new AgentCronDto(
                id,
                "sess-" + id,
                "usr-1",
                "chat-1",
                "telegram",
                "platform-1",
                "0 * * * *",
                "UTC",
                false,
                "goal",
                nextFireTime,
                Instant.now(),
                status,
                "role",
                null,
                "deliv-1",
                Instant.now(),
                Instant.now());
    }

    @Test
    void testFindDueCrons() {
        AgentCronDto due =
                createCron("c1", CronStatus.ACTIVE.name(), Instant.now().minusSeconds(60));
        AgentCronDto future =
                createCron("c2", CronStatus.ACTIVE.name(), Instant.now().plusSeconds(600));
        AgentCronDto paused =
                createCron("c3", CronStatus.FAILED.name(), Instant.now().minusSeconds(60));

        when(store.list(DOMAIN_BY_ID, ".json")).thenReturn(List.of("c1.json", "c2.json", "c3.json"));
        when(store.readOptional(List.of("crons", "by-id", "c1.json"), AgentCronDto.class))
                .thenReturn(Optional.of(due));
        when(store.readOptional(List.of("crons", "by-id", "c2.json"), AgentCronDto.class))
                .thenReturn(Optional.of(future));
        when(store.readOptional(List.of("crons", "by-id", "c3.json"), AgentCronDto.class))
                .thenReturn(Optional.of(paused));

        List<AgentCronDto> dueList = adapter.findDueCrons();
        assertEquals(1, dueList.size());
        assertEquals("c1", dueList.get(0).id());
    }

    @Test
    void testFindByUserId() {
        AgentCronDto c1 = createCron("c1", CronStatus.ACTIVE.name(), Instant.now());
        when(store.list(DOMAIN_BY_ID, ".json")).thenReturn(List.of("c1.json"));
        when(store.readOptional(List.of("crons", "by-id", "c1.json"), AgentCronDto.class))
                .thenReturn(Optional.of(c1));

        List<AgentCronDto> list = adapter.findByUserId("usr-1");
        assertEquals(1, list.size());
        assertTrue(adapter.findByUserId("usr-other").isEmpty());
    }

    @Test
    void testFindAwaitingApproval() {
        AgentCronDto c1 = createCron("c1", CronStatus.AWAITING_APPROVAL.name(), Instant.now());
        when(store.list(DOMAIN_BY_ID, ".json")).thenReturn(List.of("c1.json"));
        when(store.readOptional(List.of("crons", "by-id", "c1.json"), AgentCronDto.class))
                .thenReturn(Optional.of(c1));

        List<AgentCronDto> listUser = adapter.findAwaitingApprovalByUserId("usr-1");
        assertEquals(1, listUser.size());

        List<AgentCronDto> listAll = adapter.findAllAwaitingApproval();
        assertEquals(1, listAll.size());
    }

    @Test
    void testClaimForFireSuccess() {
        AgentCronDto cron = createCron("c1", CronStatus.ACTIVE.name(), Instant.now());
        when(store.readOptional(List.of("crons", "by-id", "c1.json"), AgentCronDto.class))
                .thenReturn(Optional.of(cron));

        boolean claimed = adapter.claimForFire("c1");
        assertTrue(claimed);

        ArgumentCaptor<AgentCronDto> captor = ArgumentCaptor.forClass(AgentCronDto.class);
        verify(store).write(any(), captor.capture());
        assertEquals(CronStatus.RUNNING.name(), captor.getValue().status());
    }

    @Test
    void testStatusMutations() {
        AgentCronDto cron = createCron("c1", CronStatus.RUNNING.name(), Instant.now());
        when(store.readOptional(List.of("crons", "by-id", "c1.json"), AgentCronDto.class))
                .thenReturn(Optional.of(cron));

        adapter.releaseClaimToActive("c1");
        adapter.markCompleted("c1", Instant.now());
        adapter.markAwaitingApproval("c1");
        adapter.markRunning("c1");
        adapter.setDeliveryId("c1", "d-123");
        adapter.markFailed("c1", "error");
        adapter.markSkipped("c1", Instant.now().plusSeconds(60), Instant.now());

        verify(store, atLeast(7)).write(any(), any());
    }

    @Test
    void testReclaimStaleCrons() {
        AgentCronDto stale =
                createCron("c1", CronStatus.RUNNING.name(), Instant.now().minusSeconds(3600));
        stale = new AgentCronDto(
                stale.id(),
                stale.sessionId(),
                stale.userId(),
                stale.chatId(),
                stale.channelType(),
                stale.channelPlatformId(),
                stale.cronExpression(),
                stale.timezone(),
                stale.runOnce(),
                stale.goal(),
                stale.nextFireTime(),
                Instant.now().minusSeconds(7200),
                stale.status(),
                stale.executionRole(),
                stale.errorReason(),
                stale.lastDeliveryId(),
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(7200));

        when(store.list(DOMAIN_BY_ID, ".json")).thenReturn(List.of("c1.json"));
        when(store.readOptional(List.of("crons", "by-id", "c1.json"), AgentCronDto.class))
                .thenReturn(Optional.of(stale));

        adapter.reclaimStaleCrons(Instant.now().minusSeconds(3600));
        verify(store).write(any(), any());
    }

    @Test
    void testSaveAndDeleteOperations() {
        AgentCronDto cron = createCron("c1", CronStatus.ACTIVE.name(), Instant.now());
        when(store.readOptional(eq(List.of("crons", "indices", "by-user", "usr-1.json")), any()))
                .thenReturn(Optional.empty());

        adapter.save(cron);
        verify(store).write(eq(List.of("crons", "by-id", "c1.json")), eq(cron));

        when(store.readOptional(List.of("crons", "by-id", "c1.json"), AgentCronDto.class))
                .thenReturn(Optional.of(cron));
        int deleted = adapter.deleteByIdAndUserId("c1", "usr-1");
        assertEquals(1, deleted);

        when(store.list(DOMAIN_BY_ID, ".json")).thenReturn(List.of("c1.json"));
        long countSession = adapter.deleteAllBySessionId("sess-c1");
        assertEquals(1, countSession);
    }

    @Test
    void testFindAllAndPagination() {
        AgentCronDto c1 =
                createCron("c1", CronStatus.ACTIVE.name(), Instant.now().plusSeconds(100));
        AgentCronDto c2 =
                createCron("c2", CronStatus.FAILED.name(), Instant.now().plusSeconds(200));
        when(store.list(DOMAIN_BY_ID, ".json")).thenReturn(List.of("c1.json", "c2.json"));
        when(store.readOptional(List.of("crons", "by-id", "c1.json"), AgentCronDto.class))
                .thenReturn(Optional.of(c1));
        when(store.readOptional(List.of("crons", "by-id", "c2.json"), AgentCronDto.class))
                .thenReturn(Optional.of(c2));

        assertEquals(2, adapter.countAll(null));
        assertEquals(1, adapter.countAll(CronStatus.ACTIVE.name()));

        List<AgentCronDto> all = adapter.findAll(null, 0, 10);
        assertEquals(2, all.size());

        List<AgentCronDto> byUser = adapter.findByUserId("usr-1", CronStatus.ACTIVE.name(), 0, 10);
        assertEquals(1, byUser.size());
        assertEquals(1, adapter.countByUserId("usr-1", CronStatus.ACTIVE.name()));
    }

    @Test
    void testRenewLeaseAndAtomicOwnership() {
        AtomicReference<AgentCronDto> state = new AtomicReference<>(new AgentCronDto(
                "c1",
                "sess-c1",
                "usr-1",
                "chat-1",
                "telegram",
                "platform-1",
                "0 * * * *",
                "UTC",
                false,
                "goal",
                Instant.now(),
                null,
                CronStatus.ACTIVE.name(),
                "role",
                null,
                null,
                Instant.now(),
                Instant.now()));

        when(store.readOptional(eq(List.of("crons", "by-id", "c1.json")), eq(AgentCronDto.class)))
                .thenAnswer(inv -> Optional.ofNullable(state.get()));
        doAnswer(inv -> {
                    state.set(inv.getArgument(1));
                    return null;
                })
                .when(store)
                .write(eq(List.of("crons", "by-id", "c1.json")), any(AgentCronDto.class));

        // 1. Claim for fire with delivery-123
        assertTrue(adapter.claimForFire("c1", "delivery-123"));
        assertEquals(CronStatus.RUNNING.name(), state.get().status());
        assertEquals("delivery-123", state.get().lastDeliveryId());

        // 2. Competing claim should fail
        assertFalse(adapter.claimForFire("c1", "delivery-456"));

        // 3. Renew lease with wrong owner fails, correct owner succeeds
        assertFalse(adapter.renewLease("c1", "wrong-owner"));
        assertTrue(adapter.renewLease("c1", "delivery-123"));

        // 4. Mark awaiting approval by owner transitions state
        assertFalse(adapter.markAwaitingApprovalIfOwner("c1", "wrong-owner"));
        assertTrue(adapter.markAwaitingApprovalIfOwner("c1", "delivery-123"));
        assertEquals(CronStatus.AWAITING_APPROVAL.name(), state.get().status());

        // 5. While AWAITING_APPROVAL, renewLease must fail because status is not RUNNING
        assertFalse(adapter.renewLease("c1", "delivery-123"));

        // 6. Resume claim transitions back to RUNNING with new deliveryId
        assertFalse(adapter.claimForResume("c1", ""));
        assertTrue(adapter.claimForResume("c1", "delivery-789"));
        assertEquals(CronStatus.RUNNING.name(), state.get().status());
        assertEquals("delivery-789", state.get().lastDeliveryId());

        // 7. Mark completed by new owner transitions to COMPLETED
        assertFalse(adapter.markCompletedIfOwner("c1", "delivery-123", Instant.now()));
        assertTrue(adapter.markCompletedIfOwner("c1", "delivery-789", Instant.now()));
        assertEquals(CronStatus.COMPLETED.name(), state.get().status());

        // 8. Further updates fail because status is COMPLETED
        assertFalse(adapter.renewLease("c1", "delivery-789"));
        assertFalse(adapter.markFailedIfOwner("c1", "delivery-789", "err"));
    }
}
