package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.loop.CheckpointResumeCoordinator;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetryReflexTest {

    @Mock
    StatefulAgentStore agentStore;

    @InjectMocks
    RetryReflex reflex;

    private MessageContext context() {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/retry", "sess-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void matchesRecognizesSlashRetry() {
        assertTrue(reflex.matches("/retry"));
        assertTrue(reflex.matches("  /RETRY  "));
    }

    @Test
    void rejectsNonRetryInput() {
        assertFalse(reflex.matches("/reset"));
        assertFalse(reflex.matches("retry"));
        assertFalse(reflex.matches(null));
    }

    @Test
    void returnsErrorWhenNoArchivedCheckpoint() {
        when(agentStore.findLatestByStatus("sess-1", CheckpointStatus.ARCHIVED)).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/retry", context());

        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("No previously cancelled"));
    }

    @Test
    void forwardsOriginalGoalForStaleDiscard() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getOriginalGoal()).thenReturn("build a REST API");
        when(cp.getFailReason()).thenReturn(CheckpointResumeCoordinator.FAIL_REASON_STALE_DISCARD);
        when(agentStore.findLatestByStatus("sess-1", CheckpointStatus.ARCHIVED)).thenReturn(Optional.of(cp));

        MessageContext ctx = context();
        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/retry", ctx);

        assertFalse(result.commandResponse().isPresent());
        assertEquals("build a REST API", result.sanitizedInput());
        assertEquals("build a REST API", ctx.getGoal());
    }

    @Test
    void returnsErrorWhenNotStaleDiscard() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getOriginalGoal()).thenReturn("some goal");
        when(cp.getFailReason()).thenReturn("some other reason");
        when(agentStore.findLatestByStatus("sess-1", CheckpointStatus.ARCHIVED)).thenReturn(Optional.of(cp));

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/retry", context());

        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("cannot be retried"));
    }

    @Test
    void returnsErrorWhenOriginalGoalIsNull() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getOriginalGoal()).thenReturn(null);
        when(agentStore.findLatestByStatus("sess-1", CheckpointStatus.ARCHIVED)).thenReturn(Optional.of(cp));

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/retry", context());

        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("cannot be retried"));
    }
}
