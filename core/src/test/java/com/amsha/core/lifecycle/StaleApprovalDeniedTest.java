package com.amsha.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.event.Event;

import java.time.Instant;
import java.util.List;

import com.amsha.channel.api.hitl.ApprovalDecision;
import com.amsha.channel.api.hitl.ApprovalDecisionEvent;
import com.amsha.channel.api.hitl.ApprovalOutcome;
import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.agent.trace.AgentTraceEvent;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.runtime.StartupEvent;

/**
 * Issue 03 AC2 — stale approvals: on restart every channel's pending approvals are denied, and the
 * trace shows them as denied / {@code hitl_timeout}, never "pending".
 *
 * <p>The denial itself is a pre-existing invariant in
 * {@link SessionReaper#doRecoverZombies()}; this test additionally pins the trace emission that
 * makes the terminal state visible to the Visual Cockpit.
 */
@ExtendWith(MockitoExtension.class)
class StaleApprovalDeniedTest {

    @Mock
    CheckpointStorePort checkpointStore;

    @Mock
    Event<ApprovalDecisionEvent> decisionEvents;

    @Mock
    CliResultBridge cliResultBridge;

    @Mock
    AgentSseService agentSseService;

    @Mock
    TracePublisher tracePublisher;

    @InjectMocks
    SessionReaper reaper;

    @BeforeEach
    void setUp() {
        reaper.sessionTtlMinutes = 30;
        reaper.hitlTimeoutHours = 24;
        reaper.retentionDays = 30;
        reaper.archiveAfterDays = 90;
    }

    private static CheckpointDto pending(long id, String sessionId, String channelType, String tool) {
        return new CheckpointDto(
                id,
                sessionId,
                "DEFAULT",
                CheckpointStatus.AWAITING_APPROVAL.name(),
                null,
                null,
                "platform-1",
                channelType,
                null,
                null,
                null,
                null,
                tool,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                Instant.now(),
                Instant.now());
    }

    private static CheckpointDto pending(String sessionId, String channelType, String tool) {
        return pending(1L, sessionId, channelType, tool);
    }

    @Test
    void restartDeniesStaleApprovalsOnEveryChannelAndTracesThem() {
        when(checkpointStore.recoverInProgressZombies(any(Instant.class), anyString()))
                .thenReturn(List.of());
        when(checkpointStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                .thenReturn(List.of(
                        pending(1L, "cli-sess", "CLI", "shell_tool"),
                        pending(2L, "tg-sess", "TELEGRAM", "web_search")));

        reaper.onStartup(new StartupEvent());

        // Every pending approval is transitioned to DENIED, regardless of channel.
        verify(checkpointStore).transitionStatus(1L, CheckpointStatus.DENIED.name());
        verify(checkpointStore).transitionStatus(2L, CheckpointStatus.DENIED.name());

        // Every channel is notified with an explicit REJECT / EXPIRED decision.
        ArgumentCaptor<ApprovalDecisionEvent> decisionCaptor = ArgumentCaptor.forClass(ApprovalDecisionEvent.class);
        verify(decisionEvents, times(2)).fireAsync(decisionCaptor.capture());
        assertEquals(
                ApprovalDecision.REJECT, decisionCaptor.getAllValues().get(0).decision());
        assertEquals(
                ApprovalOutcome.EXPIRED, decisionCaptor.getAllValues().get(0).outcome());
        assertEquals("cli-sess", decisionCaptor.getAllValues().get(0).sessionId());
        assertEquals("tg-sess", decisionCaptor.getAllValues().get(1).sessionId());

        // CLI additionally gets its pending bridge cancelled; Telegram must not.
        verify(cliResultBridge).cancel("cli-sess");
        verify(cliResultBridge, times(0)).cancel("tg-sess");
    }

    @Test
    void restartEmitsHitlTimeoutTraceNeverPending() {
        when(checkpointStore.recoverInProgressZombies(any(Instant.class), anyString()))
                .thenReturn(List.of());
        when(checkpointStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                .thenReturn(List.of(pending("sess-1", "TELEGRAM", "shell_tool")));

        reaper.onStartup(new StartupEvent());

        ArgumentCaptor<AgentTraceEvent> traceCaptor = ArgumentCaptor.forClass(AgentTraceEvent.class);
        verify(tracePublisher).publish(traceCaptor.capture());
        AgentTraceEvent event = traceCaptor.getValue();

        assertEquals(TraceStage.HITL_TIMEOUT, event.stage(), "restart denial must be traced as hitl_timeout");
        assertEquals(StationCode.STATION_05_TOOL_GUARD, event.station());
        assertEquals("DENIED_RESTART", event.status(), "denied on restart — never 'pending'");
        assertEquals("sess-1", event.sessionId());
        assertEquals("shell_tool", event.detail());
    }

    @Test
    void traceIsPublishedBeforeSseStreamIsCompleted() {
        // Ordering matters: if the stream were completed first, a connected subscriber would
        // never observe the terminal state.
        when(checkpointStore.recoverInProgressZombies(any(Instant.class), anyString()))
                .thenReturn(List.of());
        when(checkpointStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                .thenReturn(List.of(pending("sess-1", "TELEGRAM", "shell_tool")));

        reaper.onStartup(new StartupEvent());

        InOrder order = inOrder(tracePublisher, agentSseService);
        order.verify(tracePublisher).publish(any(AgentTraceEvent.class));
        order.verify(agentSseService).complete("sess-1");
    }
}
