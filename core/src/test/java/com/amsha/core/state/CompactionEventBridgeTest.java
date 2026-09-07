package com.amsha.core.state;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.amsha.core.agent.trace.AgentTraceEvent;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.compaction.CompactionInFlightFlag;
import com.amsha.core.compaction.CompactionRequest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompactionEventBridgeTest {

    @Mock
    private CompactionService compactionService;

    @Mock
    private CompactionInFlightFlag compactionInFlight;

    @Mock
    private StatefulAgentStore agentStore;

    @Mock
    private TracePublisher tracePublisher;

    @InjectMocks
    private CompactionEventBridge bridge;

    @Test
    void testDelegatesToServiceAndReleasesLockOnSuccess() {
        CompactionRequest request = new CompactionRequest("session1", "user1");
        when(agentStore.findLatestAwaitingApproval(anyString())).thenReturn(Optional.empty());
        when(compactionService.compact("session1", "user1")).thenReturn(true);

        bridge.onCompactionRequested(request);

        verify(compactionService).compact("session1", "user1");
        verify(compactionInFlight).set("session1", false);

        ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);
        verify(tracePublisher).publish(captor.capture());
        Assertions.assertEquals(TraceStage.CONSOLIDATION, captor.getValue().stage());
        Assertions.assertEquals(
                StationCode.STATION_09_CONSOLIDATION, captor.getValue().station());
    }

    @Test
    void testReleasesLockOnFailure() {
        CompactionRequest request = new CompactionRequest("session1", "user1");
        when(agentStore.findLatestAwaitingApproval(anyString())).thenReturn(Optional.empty());
        when(compactionService.compact(anyString(), anyString())).thenThrow(new RuntimeException("Compaction failed"));

        bridge.onCompactionRequested(request);

        verify(compactionInFlight).set("session1", false);
    }
}
