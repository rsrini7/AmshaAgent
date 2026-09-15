package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronStorePort;

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
    CronStorePort cronStore;

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
}
