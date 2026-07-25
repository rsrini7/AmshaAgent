package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.state.CronStatus;
import com.amsha.persistence.api.AgentCronDto;

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
    void testClaimForFireSuccess() {
        lenient().when(locks.tryLock(anyString(), anyInt())).thenReturn(true);
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
    void testMarkFired() {
        lenient().when(locks.tryLock(anyString(), anyInt())).thenReturn(true);
        AgentCronDto cron = createCron("c1", CronStatus.RUNNING.name(), Instant.now());
        when(store.readOptional(List.of("crons", "by-id", "c1.json"), AgentCronDto.class))
                .thenReturn(Optional.of(cron));

        Instant nextFire = Instant.now().plusSeconds(3600);
        adapter.markFired("c1", Instant.now(), nextFire);

        ArgumentCaptor<AgentCronDto> captor = ArgumentCaptor.forClass(AgentCronDto.class);
        verify(store).write(any(), captor.capture());
        assertEquals(CronStatus.ACTIVE.name(), captor.getValue().status());
        assertEquals(nextFire, captor.getValue().nextFireTime());
    }
}
