package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;
import com.amsha.security.api.RiskLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class RateLimitRetrySchedulerTest {

    private static final String JOB_ID = "12345678-1234-1234-1234-123456789012";
    private static final String DELIVERY_ID = "4612feff-431b-4939-a91e-af7ac7d5156c";

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    AgentLoop agentLoop;

    @Mock
    ChannelPluginRegistry channelPluginRegistry;

    @Mock
    ChannelPlugin channelPlugin;

    @Mock
    ChannelSender channelSender;

    @Mock
    CronStorePort cronStore;

    @Mock
    CronRunStorePort cronRunStore;

    @Mock
    SessionCatalogStore sessionCatalogStore;

    @Spy
    MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    RateLimitRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler.defaultRetryDelayMs = 1000;
        scheduler.init();
    }

    @Test
    void testProcessPendingRetriesEmpty() {
        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of());
        assertDoesNotThrow(() -> scheduler.processPendingRetries());
    }

    @Test
    void testProcessPendingRetriesReadyToResume() {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(100L);
        cp.setSessionId("sess-retry");
        cp.setAgentRole("coder");
        cp.setOriginalGoal("do task");
        cp.setUpdatedAt(Instant.now().minusSeconds(10));
        cp.setRetryAfterMs(1000);

        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));
        when(agentStore.claimForRetry(100L)).thenReturn(true);

        assertDoesNotThrow(() -> scheduler.processPendingRetries());
        verify(agentLoop).resume("sess-retry", "coder", "do task");
    }

    @Test
    void backgroundRetryCompletionIsDeliveredToCli() {
        AgentCheckpointRecord cp = readyRetryCheckpoint("sess-retry");
        stubNotificationRoute(cp.getSessionId(), ChannelType.CLI, "cli-owner", "cli-chat");
        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));
        when(agentStore.claimForRetry(cp.getId())).thenReturn(true);
        when(agentLoop.resume(cp.getSessionId(), cp.getAgentRole(), cp.getOriginalGoal()))
                .thenReturn(new AgentResult.Finished("The retry completed.", List.of(), 0, 0));

        scheduler.processPendingRetries();

        verify(channelSender)
                .sendRaw(
                        eq(new ChannelUser("cli-owner", "cli-chat", "User", ChannelType.CLI)),
                        contains("The retry completed."));
    }

    @Test
    void backgroundRetryCanRequestTelegramApproval() {
        AgentCheckpointRecord cp = readyRetryCheckpoint("sess-telegram");
        stubNotificationRoute(cp.getSessionId(), ChannelType.TELEGRAM, "telegram-user", "telegram-chat");
        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));
        when(agentStore.claimForRetry(cp.getId())).thenReturn(true);
        when(agentLoop.resume(cp.getSessionId(), cp.getAgentRole(), cp.getOriginalGoal()))
                .thenReturn(new AgentResult.AwaitingApproval(
                        new ApprovalFrame("execute_shell", Map.of("command", "ls"), RiskLevel.HIGH),
                        cp.getSessionId(),
                        List.of(),
                        0,
                        0));

        scheduler.processPendingRetries();

        verify(channelSender)
                .send(
                        eq(new ChannelUser("telegram-user", "telegram-chat", "User", ChannelType.TELEGRAM)),
                        argThat(nodes -> nodes.size() == 1
                                && nodes.getFirst() instanceof ApprovalPrompt prompt
                                && prompt.toolName().equals("execute_shell")
                                && prompt.sessionId().equals(cp.getSessionId())));
    }

    @Test
    void backgroundRetryThatHitsAnotherLimitNotifiesTelegramOfNewDelay() {
        AgentCheckpointRecord cp = readyRetryCheckpoint("sess-telegram");
        stubNotificationRoute(cp.getSessionId(), ChannelType.TELEGRAM, "telegram-user", "telegram-chat");
        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));
        when(agentStore.claimForRetry(cp.getId())).thenReturn(true);
        when(agentLoop.resume(cp.getSessionId(), cp.getAgentRole(), cp.getOriginalGoal()))
                .thenReturn(new AgentResult.AwaitingRetry(cp.getSessionId(), 5000, List.of(), 0, 0));

        scheduler.processPendingRetries();

        verify(channelSender)
                .sendRaw(
                        eq(new ChannelUser("telegram-user", "telegram-chat", "User", ChannelType.TELEGRAM)),
                        contains("Automatically retrying in 5s"));
    }

    @Test
    void backgroundRetryFailureIsDeliveredToTelegram() {
        AgentCheckpointRecord cp = readyRetryCheckpoint("sess-telegram");
        stubNotificationRoute(cp.getSessionId(), ChannelType.TELEGRAM, "telegram-user", "telegram-chat");
        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));
        when(agentStore.claimForRetry(cp.getId())).thenReturn(true);
        when(agentLoop.resume(cp.getSessionId(), cp.getAgentRole(), cp.getOriginalGoal()))
                .thenThrow(new RuntimeException("provider unavailable"));

        scheduler.processPendingRetries();

        verify(channelSender)
                .sendRaw(
                        eq(new ChannelUser("telegram-user", "telegram-chat", "User", ChannelType.TELEGRAM)),
                        contains("Automatic retry failed"));
    }

    @Test
    void testProcessPendingRetriesStillInBackoff() {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(101L);
        cp.setSessionId("sess-wait");
        cp.setUpdatedAt(Instant.now());
        cp.setRetryAfterMs(60000);

        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));

        assertDoesNotThrow(() -> scheduler.processPendingRetries());
        verify(agentLoop, never()).resume(any(), any(), any());
    }

    @Test
    void testSweepOrphanedRetries() {
        AgentCheckpointRecord stale = new AgentCheckpointRecord();
        stale.setId(200L);
        stale.setUpdatedAt(Instant.now().minusSeconds(3600));

        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(stale));

        assertDoesNotThrow(() -> scheduler.sweepOrphanedRetries());
        verify(agentStore).markOrphaned(eq(200L), anyString());
    }

    @Test
    void testLifecycleShutdownGuards() {
        scheduler.onShutdown(null);
        scheduler.processPendingRetries();
        scheduler.sweepOrphanedRetries();
        verify(agentStore, never()).findByStatus(any());

        scheduler.destroy();
    }

    @Test
    void processPendingRetriesCronLeaseLostBeforeResume() {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(100L);
        cp.setSessionId("cron-" + JOB_ID + "-" + DELIVERY_ID);
        cp.setAgentRole("coder");
        cp.setOriginalGoal("do task");
        cp.setUpdatedAt(Instant.now().minusSeconds(10));
        cp.setRetryAfterMs(1000);

        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));
        when(agentStore.claimForRetry(100L)).thenReturn(true);
        when(cronStore.claimForResume(JOB_ID, DELIVERY_ID)).thenReturn(false);
        when(cronStore.renewLease(JOB_ID, DELIVERY_ID)).thenReturn(false);

        scheduler.processPendingRetries();

        verify(agentStore).markFailed(eq(100L), contains("Cron lease lost before retry resume"));
        verify(agentLoop, never()).resume(any(), any(), any());
    }

    @Test
    void processPendingRetriesCronLeaseLostDuringResume() {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(101L);
        cp.setSessionId("cron-" + JOB_ID + "-" + DELIVERY_ID);
        cp.setAgentRole("coder");
        cp.setOriginalGoal("do task");
        cp.setUpdatedAt(Instant.now().minusSeconds(10));
        cp.setRetryAfterMs(1000);

        AgentCronDto dto = new AgentCronDto(
                JOB_ID, null, null, null, null, null, null, null, false, null, null, null, null, null, null, null, null,
                null);
        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));
        when(agentStore.claimForRetry(101L)).thenReturn(true);
        when(cronStore.claimForResume(JOB_ID, DELIVERY_ID)).thenReturn(true);
        when(cronStore.findById(JOB_ID)).thenReturn(Optional.of(dto));
        when(cronStore.markFailedIfOwner(eq(JOB_ID), eq(DELIVERY_ID), anyString()))
                .thenReturn(false);
        when(agentLoop.resume(any(), any(), any()))
                .thenReturn(AgentResult.failed("x", AgentResult.FailureCategory.SYSTEM_ERROR, List.of()));

        scheduler.processPendingRetries();

        verify(agentStore).markFailed(eq(101L), contains("Cron lease lost during retry resume"));
    }

    @Test
    void processPendingRetriesResumeThrows() {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(102L);
        cp.setSessionId("sess-retry");
        cp.setAgentRole("coder");
        cp.setOriginalGoal("do task");
        cp.setUpdatedAt(Instant.now().minusSeconds(10));
        cp.setRetryAfterMs(1000);

        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));
        when(agentStore.claimForRetry(102L)).thenReturn(true);
        when(agentLoop.resume(any(), any(), any())).thenThrow(new RuntimeException("background boom"));

        scheduler.processPendingRetries();

        verify(agentStore).markFailed(eq(102L), contains("Background retry failed: background boom"));
    }

    @Test
    void processPendingRetriesAlreadyClaimedByAnotherThread() {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(103L);
        cp.setSessionId("sess-retry");
        cp.setUpdatedAt(Instant.now().minusSeconds(10));
        cp.setRetryAfterMs(1000);

        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));
        when(agentStore.claimForRetry(103L)).thenReturn(false);

        scheduler.processPendingRetries();

        verify(agentLoop, never()).resume(any(), any(), any());
    }

    @Test
    void processPendingRetriesRuntimeExceptionNonShutdown() {
        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY))
                .thenThrow(new RuntimeException("transient boom"));

        assertDoesNotThrow(() -> scheduler.processPendingRetries());
    }

    @Test
    void processPendingRetriesRuntimeExceptionShutdownRace() {
        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY))
                .thenThrow(new RuntimeException("HikariPool-1 pool is closed"));

        assertDoesNotThrow(() -> scheduler.processPendingRetries());
    }

    @Test
    void sweepOrphanedRetriesRuntimeExceptionNonShutdown() {
        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY))
                .thenThrow(new RuntimeException("transient boom"));

        assertDoesNotThrow(() -> scheduler.sweepOrphanedRetries());
    }

    @Test
    void sweepOrphanedRetriesRuntimeExceptionShutdownRace() {
        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY))
                .thenThrow(new RuntimeException("database closed during sweep"));

        assertDoesNotThrow(() -> scheduler.sweepOrphanedRetries());
    }

    private static AgentCheckpointRecord readyRetryCheckpoint(String sessionId) {
        AgentCheckpointRecord checkpoint = new AgentCheckpointRecord();
        checkpoint.setId(300L);
        checkpoint.setSessionId(sessionId);
        checkpoint.setAgentRole("coordinator");
        checkpoint.setOriginalGoal("complete the task");
        checkpoint.setUpdatedAt(Instant.now().minusSeconds(10));
        checkpoint.setRetryAfterMs(1000);
        return checkpoint;
    }

    private void stubNotificationRoute(String sessionId, ChannelType channelType, String userId, String channelId) {
        Instant now = Instant.now();
        SessionCatalogEntry entry = new SessionCatalogEntry(
                "entry-" + sessionId,
                userId,
                channelType.id(),
                channelId,
                sessionId,
                "coordinator",
                null,
                now,
                now,
                0,
                true,
                "ACTIVE");
        when(sessionCatalogStore.bySessionId(sessionId)).thenReturn(Optional.of(entry));
        when(channelPluginRegistry.find(channelType.id())).thenReturn(Optional.of(channelPlugin));
        when(channelPlugin.sender()).thenReturn(channelSender);
    }
}
