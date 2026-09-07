package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class CronSessionStatusSyncTest {
    private static final String JOB_ID = "12345678-1234-1234-1234-123456789012";

    @Test
    void rejectsShortCronSessionId() {
        CronStorePort store = mock(CronStorePort.class);

        assertFalse(CronSessionStatusSync.syncRunning("cron-short", store));
        verifyNoInteractions(store);
    }

    @Test
    void rejectsLegacyCronSessionWithoutOwnerToken() {
        CronStorePort store = mock(CronStorePort.class);

        assertFalse(CronSessionStatusSync.syncRunning("cron-" + JOB_ID, store));
        verifyNoInteractions(store);
    }

    @Test
    void rejectsLegacyTerminalTransitionWithoutOwnerToken() {
        CronStorePort store = mock(CronStorePort.class);

        assertFalse(
                CronSessionStatusSync.syncTerminal("cron-" + JOB_ID, AgentResult.finished("done", List.of()), store));
        verifyNoInteractions(store);
    }

    @Test
    void syncRunningExtractsOwnerTokenFromDelegatedSessionId() {
        CronStorePort store = mock(CronStorePort.class);
        String deliveryId = "4612feff-431b-4939-a91e-af7ac7d5156c";
        String delegatedSessionId = "cron-" + JOB_ID + "-" + deliveryId + ":delegate:brahma:1788447140846";
        Mockito.when(store.claimForResume(JOB_ID, deliveryId)).thenReturn(true);

        Assertions.assertTrue(CronSessionStatusSync.syncRunning(delegatedSessionId, store));
        Mockito.verify(store).claimForResume(JOB_ID, deliveryId);
    }

    @Test
    void syncTerminalExtractsOwnerTokenFromDelegatedSessionId() {
        CronStorePort store = mock(CronStorePort.class);
        String deliveryId = "4612feff-431b-4939-a91e-af7ac7d5156c";
        var cronDto = new AgentCronDto(
                JOB_ID,
                "cron-" + JOB_ID,
                "user-1",
                "chat-1",
                "TELEGRAM",
                "chat-1",
                null,
                "UTC",
                true,
                "Factorial",
                null,
                null,
                "RUNNING",
                "coder",
                deliveryId,
                null,
                null,
                null);
        Mockito.when(store.findById(JOB_ID)).thenReturn(Optional.of(cronDto));
        String delegatedSessionId = "cron-" + JOB_ID + "-" + deliveryId + ":delegate:brahma:1788447140846";
        Mockito.when(store.markCompletedIfOwner(
                        ArgumentMatchers.eq(JOB_ID),
                        ArgumentMatchers.eq(deliveryId),
                        ArgumentMatchers.any(Instant.class)))
                .thenReturn(true);

        Assertions.assertTrue(
                CronSessionStatusSync.syncTerminal(delegatedSessionId, AgentResult.finished("done", List.of()), store));
        Mockito.verify(store)
                .markCompletedIfOwner(
                        ArgumentMatchers.eq(JOB_ID),
                        ArgumentMatchers.eq(deliveryId),
                        ArgumentMatchers.any(Instant.class));
    }
}
