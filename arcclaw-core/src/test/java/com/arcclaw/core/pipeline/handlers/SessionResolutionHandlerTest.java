package com.arcclaw.core.pipeline.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.StatefulAgentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionResolutionHandlerTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    SessionHeartbeatTracker heartbeatTracker;

    @InjectMocks
    SessionResolutionHandler handler;

    @Test
    void testSessionResolutionWithStoredAutonomy() {
        ChannelUser user = new ChannelUser("u1", "User 1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "test", "chat1", Instant.now());
        ChannelSender sender = mock(ChannelSender.class);
        
        MessageContext context = new MessageContext("chat1", user, msg, sender);
        
        // Match the UUID v3 (MD5) of "chat1:u1"
        String sessionId = UUID.nameUUIDFromBytes("chat1:u1".getBytes(StandardCharsets.UTF_8)).toString();
        
        when(heartbeatTracker.getAutonomyLevel(sessionId)).thenReturn(AutonomyLevel.FULL);

        handler.handle(context);

        assertEquals(sessionId, context.getSessionId().orElse(null));
        assertEquals(AutonomyLevel.FULL, context.getAutonomyLevel().orElse(null));
    }

    @Test
    void testSessionResolutionWithCheckpointFallback() {
        ChannelUser user = new ChannelUser("u1", "User 1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "test", "chat1", Instant.now());
        ChannelSender sender = mock(ChannelSender.class);
        
        MessageContext context = new MessageContext("chat1", user, msg, sender);
        String sessionId = UUID.nameUUIDFromBytes("chat1:u1".getBytes(StandardCharsets.UTF_8)).toString();

        when(heartbeatTracker.getAutonomyLevel(sessionId)).thenReturn(null);
        when(agentStore.loadLatestAutonomyForSession(sessionId)).thenReturn(Optional.of(AutonomyLevel.READONLY));

        handler.handle(context);

        assertEquals(AutonomyLevel.READONLY, context.getAutonomyLevel().orElse(null));
    }

    @Test
    void testSessionResolutionDefaultsToSupervised() {
        ChannelUser user = new ChannelUser("u1", "User 1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "test", "chat1", Instant.now());
        ChannelSender sender = mock(ChannelSender.class);
        
        MessageContext context = new MessageContext("chat1", user, msg, sender);
        String sessionId = UUID.nameUUIDFromBytes("chat1:u1".getBytes(StandardCharsets.UTF_8)).toString();

        when(heartbeatTracker.getAutonomyLevel(sessionId)).thenReturn(null);
        when(agentStore.loadLatestAutonomyForSession(sessionId)).thenReturn(Optional.empty());

        handler.handle(context);

        assertEquals(AutonomyLevel.SUPERVISED, context.getAutonomyLevel().orElse(null));
    }
}
