package com.arcclaw.core.state;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @InjectMocks
    private CompactionEventBridge bridge;

    @Test
    void testDelegatesToServiceAndReleasesLockOnSuccess() {
        CompactionRequest request = new CompactionRequest("session1", "user1");
        when(agentStore.findLatestAwaitingApproval(anyString())).thenReturn(java.util.Optional.empty());
        
        bridge.onCompactionRequested(request);

        verify(compactionService).compact("session1", "user1");
        verify(compactionInFlight).set("session1", false);
    }

    @Test
    void testReleasesLockOnFailure() {
        CompactionRequest request = new CompactionRequest("session1", "user1");
        when(agentStore.findLatestAwaitingApproval(anyString())).thenReturn(java.util.Optional.empty());
        when(compactionService.compact(anyString(), anyString())).thenThrow(new RuntimeException("Compaction failed"));

        bridge.onCompactionRequested(request);

        verify(compactionInFlight).set("session1", false);
    }
}
