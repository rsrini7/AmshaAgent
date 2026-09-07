package com.amsha.core.lifecycle;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CronFailureCleanupTest {

    private static final String CRON_ID = "cron-123";

    @Mock
    private StatefulAgentStore agentStore;

    @Mock
    private CronStorePort cronStore;

    @Mock
    private CronRunStorePort cronRunStore;

    private CronFailureCleanup cleanup;

    @BeforeEach
    void setUp() {
        cleanup = new CronFailureCleanup();
        cleanup.agentStore = agentStore;
        cleanup.cronStore = cronStore;
        cleanup.cronRunStore = cronRunStore;
    }

    @Test
    void staleApprovalWithoutCheckpointIsFailed() {
        AgentCronDto cron = cron(CRON_ID, null, CronStatus.AWAITING_APPROVAL.name(), "researcher");
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(cron));
        when(agentStore.load("cron-" + CRON_ID, "researcher")).thenReturn(Optional.empty());

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(cronStore).markFailedIfStatus(eq(CRON_ID), eq(CronStatus.AWAITING_APPROVAL.name()), anyString());
    }

    @Test
    void liveApprovalCheckpointIsPreserved() {
        AgentCronDto cron = cron(CRON_ID, "delivery-1", CronStatus.AWAITING_APPROVAL.name(), "researcher");
        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.AWAITING_APPROVAL);
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(cron));
        when(agentStore.load("cron-" + CRON_ID + "-delivery-1", "researcher")).thenReturn(Optional.of(checkpoint));

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(cronStore, never()).markFailedIfStatus(anyString(), anyString(), anyString());
    }

    private static AgentCronDto cron(String id, String deliveryId, String status, String executionRole) {
        Instant stale = Instant.now().minus(25, ChronoUnit.HOURS);
        return new AgentCronDto(
                id,
                "origin-session",
                "user-1",
                "chat-1",
                "WEBSOCKET",
                "platform-1",
                "0 0 * * *",
                "UTC",
                true,
                "goal",
                stale,
                null,
                status,
                executionRole,
                null,
                deliveryId,
                stale,
                stale);
    }
}
