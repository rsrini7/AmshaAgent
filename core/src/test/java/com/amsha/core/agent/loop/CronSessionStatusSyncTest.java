package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class CronSessionStatusSyncTest {
    private static final String JOB_ID = "12345678-1234-1234-1234-123456789012";
    private static final String DELIVERY_ID = "4612feff-431b-4939-a91e-af7ac7d5156c";

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
    void syncRunningRenewsLeaseWhenAlreadyRunning() {
        CronStorePort store = mock(CronStorePort.class);
        String deliveryId = "4612feff-431b-4939-a91e-af7ac7d5156c";
        String sessionId = "cron-" + JOB_ID + "-" + deliveryId;
        Mockito.when(store.claimForResume(JOB_ID, deliveryId)).thenReturn(false);
        Mockito.when(store.renewLease(JOB_ID, deliveryId)).thenReturn(true);

        Assertions.assertTrue(CronSessionStatusSync.syncRunning(sessionId, store));
        Mockito.verify(store).claimForResume(JOB_ID, deliveryId);
        Mockito.verify(store).renewLease(JOB_ID, deliveryId);
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

    private static AgentCronRecord cron(boolean runOnce, String cronExpression, String timezone) {
        AgentCronDto dto = new AgentCronDto(
                JOB_ID,
                null,
                null,
                null,
                null,
                null,
                cronExpression,
                timezone,
                runOnce,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        return AgentCronRecord.fromDto(dto);
    }

    @Test
    void syncRunningCatchesStoreException() {
        CronStorePort store = mock(CronStorePort.class);
        Mockito.when(store.claimForResume(JOB_ID, DELIVERY_ID)).thenThrow(new RuntimeException("boom"));

        Assertions.assertFalse(CronSessionStatusSync.syncRunning("cron-" + JOB_ID + "-" + DELIVERY_ID, store));
    }

    @Test
    void syncTerminalCatchesStoreException() {
        CronStorePort store = mock(CronStorePort.class);
        Mockito.when(store.findById(JOB_ID)).thenThrow(new RuntimeException("boom"));

        Assertions.assertFalse(CronSessionStatusSync.syncTerminal(
                "cron-" + JOB_ID + "-" + DELIVERY_ID, AgentResult.finished("done", List.of()), store));
    }

    @Test
    void applyTerminalFinishedRunOnceDeliveryIdNull() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(true, null, null);

        Assertions.assertTrue(
                CronSessionStatusSync.applyTerminal(c, null, AgentResult.finished("done", List.of()), store));
        Mockito.verify(store).markCompleted(ArgumentMatchers.eq(JOB_ID), ArgumentMatchers.any(Instant.class));
    }

    @Test
    void applyTerminalFinishedRunOnceFalseDeliveryIdPresent() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(false, "0 0 * * *", "UTC");
        Mockito.when(store.markFiredIfOwner(
                        ArgumentMatchers.eq(JOB_ID),
                        ArgumentMatchers.eq(DELIVERY_ID),
                        ArgumentMatchers.any(Instant.class),
                        ArgumentMatchers.any(Instant.class)))
                .thenReturn(true);

        Assertions.assertTrue(
                CronSessionStatusSync.applyTerminal(c, DELIVERY_ID, AgentResult.finished("done", List.of()), store));
        Mockito.verify(store)
                .markFiredIfOwner(
                        ArgumentMatchers.eq(JOB_ID),
                        ArgumentMatchers.eq(DELIVERY_ID),
                        ArgumentMatchers.any(Instant.class),
                        ArgumentMatchers.any(Instant.class));
    }

    @Test
    void applyTerminalFinishedRunOnceFalseDeliveryIdNull() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(false, "0 0 * * *", "UTC");

        Assertions.assertTrue(
                CronSessionStatusSync.applyTerminal(c, null, AgentResult.finished("done", List.of()), store));
        Mockito.verify(store)
                .markFired(
                        ArgumentMatchers.eq(JOB_ID),
                        ArgumentMatchers.any(Instant.class),
                        ArgumentMatchers.any(Instant.class));
    }

    @Test
    void applyTerminalFailedDeliveryIdPresent() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(true, null, null);
        Mockito.when(store.markFailedIfOwner(
                        ArgumentMatchers.eq(JOB_ID), ArgumentMatchers.eq(DELIVERY_ID), ArgumentMatchers.anyString()))
                .thenReturn(true);

        Assertions.assertTrue(CronSessionStatusSync.applyTerminal(
                c,
                DELIVERY_ID,
                AgentResult.failed("boom", AgentResult.FailureCategory.SYSTEM_ERROR, List.of()),
                store));
        Mockito.verify(store)
                .markFailedIfOwner(
                        ArgumentMatchers.eq(JOB_ID), ArgumentMatchers.eq(DELIVERY_ID), ArgumentMatchers.anyString());
    }

    @Test
    void applyTerminalFailedDeliveryIdNull() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(true, null, null);

        Assertions.assertTrue(CronSessionStatusSync.applyTerminal(
                c, null, AgentResult.failed("boom", AgentResult.FailureCategory.SYSTEM_ERROR, List.of()), store));
        Mockito.verify(store).markFailed(ArgumentMatchers.eq(JOB_ID), ArgumentMatchers.anyString());
    }

    @Test
    void applyTerminalAwaitingApprovalDeliveryIdPresent() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(true, null, null);
        Mockito.when(store.markAwaitingApprovalIfOwner(ArgumentMatchers.eq(JOB_ID), ArgumentMatchers.eq(DELIVERY_ID)))
                .thenReturn(true);

        Assertions.assertTrue(CronSessionStatusSync.applyTerminal(
                c, DELIVERY_ID, AgentResult.awaitingApproval("tool", "{}", "sess", List.of()), store));
        Mockito.verify(store)
                .markAwaitingApprovalIfOwner(ArgumentMatchers.eq(JOB_ID), ArgumentMatchers.eq(DELIVERY_ID));
    }

    @Test
    void applyTerminalAwaitingApprovalDeliveryIdNull() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(true, null, null);

        Assertions.assertTrue(CronSessionStatusSync.applyTerminal(
                c, null, AgentResult.awaitingApproval("tool", "{}", "sess", List.of()), store));
        Mockito.verify(store).markAwaitingApproval(ArgumentMatchers.eq(JOB_ID));
    }

    @Test
    void applyTerminalAwaitingRetryDeliveryIdPresent() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(true, null, null);
        Mockito.when(store.markAwaitingRetryIfOwner(ArgumentMatchers.eq(JOB_ID), ArgumentMatchers.eq(DELIVERY_ID)))
                .thenReturn(true);

        Assertions.assertTrue(CronSessionStatusSync.applyTerminal(
                c, DELIVERY_ID, AgentResult.awaitingRetry("sess", 1000, List.of()), store));
        Mockito.verify(store).markAwaitingRetryIfOwner(ArgumentMatchers.eq(JOB_ID), ArgumentMatchers.eq(DELIVERY_ID));
    }

    @Test
    void applyTerminalAwaitingRetryDeliveryIdNull() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(true, null, null);

        Assertions.assertFalse(CronSessionStatusSync.applyTerminal(
                c, null, AgentResult.awaitingRetry("sess", 1000, List.of()), store));
    }

    @Test
    void applyTerminalLoopExceededDeliveryIdPresent() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(true, null, null);
        Mockito.when(store.markFailedIfOwner(
                        ArgumentMatchers.eq(JOB_ID), ArgumentMatchers.eq(DELIVERY_ID), ArgumentMatchers.anyString()))
                .thenReturn(true);

        Assertions.assertTrue(
                CronSessionStatusSync.applyTerminal(c, DELIVERY_ID, AgentResult.loopExceeded(5, List.of()), store));
        Mockito.verify(store)
                .markFailedIfOwner(
                        ArgumentMatchers.eq(JOB_ID),
                        ArgumentMatchers.eq(DELIVERY_ID),
                        ArgumentMatchers.eq("Loop exceeded"));
    }

    @Test
    void applyTerminalLoopExceededDeliveryIdNull() {
        CronStorePort store = mock(CronStorePort.class);
        var c = cron(true, null, null);

        Assertions.assertTrue(
                CronSessionStatusSync.applyTerminal(c, null, AgentResult.loopExceeded(5, List.of()), store));
        Mockito.verify(store).markFailed(ArgumentMatchers.eq(JOB_ID), ArgumentMatchers.eq("Loop exceeded"));
    }

    @Test
    void extractExecutionIdReturnsEmptyFor37Length() {
        Assertions.assertEquals("", CronSessionStatusSync.extractExecutionId(JOB_ID + "x"));
    }
}
