package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;

import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;

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

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    AgentLoop agentLoop;

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
}
