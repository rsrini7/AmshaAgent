package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.CompactionSummaryDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatusReflexTest {

    @Mock
    StatefulAgentStore statefulAgentStore;

    @Mock
    CompactionStorePort compactionStorePort;

    @Mock
    GovernanceStrategy governanceStrategy;

    @InjectMocks
    StatusReflex reflex;

    private MessageContext context() {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/status", "sess-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void matchesRecognizesSlashStatus() {
        assertTrue(reflex.matches("/status"));
        assertTrue(reflex.matches("  /STATUS  "));
        assertTrue(reflex.matches("/Status"));
        assertTrue(reflex.matches("/token"));
        assertTrue(reflex.matches("/tokens"));
        assertTrue(reflex.matches("/usage"));
        assertTrue(reflex.matches("/stats"));
        assertTrue(reflex.matches("/model"));
    }

    @Test
    void rejectsNonStatusInput() {
        assertFalse(reflex.matches("/halt"));
        assertFalse(reflex.matches("status"));
        assertFalse(reflex.matches(""));
        assertFalse(reflex.matches(null));
    }

    @Test
    void executeShowsNotStartedWhenNoCheckpoints() {
        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.0);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(0);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(0);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("NOT_STARTED"));
    }

    @Test
    void executeShowsInProgressStatus() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        when(statefulAgentStore.load(eq("sess-1"), anyString())).thenReturn(Optional.of(cp));

        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(1.5);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(500);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(200);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        assertTrue(result.commandResponse().get().contains("IN_PROGRESS"));
        assertTrue(result.commandResponse().get().contains("1.5"));
    }

    @Test
    void executeShowsCompactedMemoryWhenSummaryExists() {
        CompactionSummaryDto summary = new CompactionSummaryDto("sess-1", "summary", Instant.now(), 15, "user1");
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.of(summary));

        when(governanceStrategy.getBudgetUsd()).thenReturn(5.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.0);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(0);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(0);

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        assertTrue(result.commandResponse().get().contains("Compacted"));
        assertTrue(result.commandResponse().get().contains("15"));
    }
}
