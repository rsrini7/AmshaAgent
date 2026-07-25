package com.amsha.core.lifecycle;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.event.Event;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import com.amsha.channel.api.hitl.ApprovalDecisionEvent;
import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SessionAuditSequencer;
import com.amsha.core.compaction.CompactionInFlightFlag;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ExtendWith(MockitoExtension.class)
class SessionReaperTest {

    @Mock
    MeterRegistry meterRegistry;

    @Mock
    Counter counter;

    @Mock
    WorkspaceManager workspaceManager;

    @Mock
    SessionHeartbeatTracker heartbeatTracker;

    @Mock
    Event<ApprovalDecisionEvent> decisionEvents;

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    SessionAuditSequencer auditSequencer;

    @Mock
    CheckpointStorePort checkpointStore;

    @Mock
    CronStorePort cronStore;

    @Mock
    CronRunStorePort cronRunStore;

    @Mock
    CompactionInFlightFlag compactionInFlight;

    @Mock
    AgentSseService agentSseService;

    @Mock
    CliResultBridge cliResultBridge;

    @Mock
    AuditService auditService;

    @Mock
    SessionCatalogStore sessionCatalogStore;

    @InjectMocks
    SessionReaper reaper;

    @BeforeEach
    void setUp() {
        reaper.sessionTtlMinutes = 30;
        reaper.hitlTimeoutHours = 24;
        reaper.retentionDays = 30;
        reaper.archiveAfterDays = 90;
    }

    @Test
    void testOnStartupRecoverZombies() {
        CheckpointDto cp1 = mock(CheckpointDto.class);
        when(cp1.id()).thenReturn(1L);
        when(cp1.sessionId()).thenReturn("sess-1");
        when(cp1.agentRole()).thenReturn("DEFAULT");
        when(checkpointStore.recoverInProgressZombies(any(Instant.class), anyString()))
                .thenReturn(List.of(cp1));

        CheckpointDto cp2 = mock(CheckpointDto.class);
        when(cp2.id()).thenReturn(2L);
        when(cp2.sessionId()).thenReturn("cli-sess-2");
        when(cp2.agentRole()).thenReturn("DEFAULT");
        when(cp2.channelType()).thenReturn("CLI");
        when(cp2.channelPlatformId()).thenReturn("platform-1");
        when(cp2.pendingToolName()).thenReturn("shell_tool");
        when(checkpointStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                .thenReturn(List.of(cp2));

        reaper.onStartup(new StartupEvent());

        verify(checkpointStore).transitionStatus(2L, CheckpointStatus.DENIED.name());
        verify(cliResultBridge).cancel("cli-sess-2");
        verify(agentSseService).complete("cli-sess-2");
    }

    @Test
    void testGcSweep() {
        when(heartbeatTracker.activeSessionCount()).thenReturn(2);
        when(checkpointStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                .thenReturn(List.of());
        when(meterRegistry.counter(anyString())).thenReturn(counter);

        doAnswer(invocation -> {
                    var consumer = invocation.getArgument(1, Consumer.class);
                    consumer.accept("expired-sess-1");
                    return null;
                })
                .when(heartbeatTracker)
                .forEachExpired(any(Instant.class), any());

        reaper.gcSweep();

        verify(workspaceManager).terminateSession("expired-sess-1");
        verify(heartbeatTracker).removeHeartbeat("expired-sess-1");
    }

    @Test
    void testSweepTimedOutApprovals() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getSessionId()).thenReturn("sess-timeout");
        when(cp.getAgentRole()).thenReturn("DEFAULT");
        when(cp.getPendingToolName()).thenReturn("tool1");
        when(cp.getId()).thenReturn(10L);
        when(cp.getChannelType()).thenReturn("CLI");
        when(cp.getChannelPlatformId()).thenReturn("p1");

        when(agentStore.findStaleAwaitingApproval(any(Instant.class))).thenReturn(List.of(cp));
        when(meterRegistry.counter(anyString(), anyString(), anyString())).thenReturn(counter);

        reaper.sweepTimedOutApprovals();

        verify(agentStore).markFailed(eq(10L), anyString());
        verify(agentSseService).complete("sess-timeout");
        verify(workspaceManager).terminateSession("sess-timeout");
    }

    @Test
    void testPurgeOldChatHistory() {
        reaper.purgeOldChatHistory();
        verify(checkpointStore).purgeOldChatHistory(any(Instant.class));
    }

    @Test
    void testArchiveInactiveCatalogSessions() {
        SessionCatalogEntry entry = new SessionCatalogEntry(
                "old-sess",
                "owner",
                "CLI",
                "ch1",
                "old-sess",
                "role",
                "goal",
                Instant.now().minusSeconds(100000),
                Instant.now().minusSeconds(100000),
                1,
                false,
                "ACTIVE");
        when(sessionCatalogStore.listInactiveBefore(any(Instant.class))).thenReturn(List.of(entry));

        reaper.archiveInactiveCatalogSessions();

        verify(sessionCatalogStore).archive("old-sess");
    }

    @Test
    void testOnShutdownSetsFlag() {
        reaper.onShutdown(new ShutdownEvent());
        reaper.gcSweep();
        verify(heartbeatTracker, never()).activeSessionCount();
    }
}
