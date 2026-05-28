package com.arcclaw.core.pipeline.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.SessionIdResolver;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.StatefulAgentStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionResolutionHandlerTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    SessionHeartbeatTracker heartbeatTracker;

    @Mock
    SessionIdResolver sessionIdResolver;

    SessionResolutionHandler handler;

    private static final String FIXED_SESSION_ID = "test-session-abc-123";

    @BeforeEach
    void setUp() {
        handler = new SessionResolutionHandler(agentStore, heartbeatTracker, sessionIdResolver);
        // All tests use the same fixed session ID from the mock resolver
        lenient()
                .when(sessionIdResolver.resolveOrCreate("TELEGRAM", "chat1", "u1"))
                .thenReturn(FIXED_SESSION_ID);
    }

    @Test
    void testSessionResolutionWithStoredAutonomy() {
        ChannelUser user = new ChannelUser("u1", "chat1", "User 1", ChannelType.SLACK);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "test", "chat1", Instant.now());
        MessageContext context = new MessageContext("chat1", user, msg);

        when(sessionIdResolver.resolveOrCreate("SLACK", "chat1", "u1")).thenReturn(FIXED_SESSION_ID);
        when(heartbeatTracker.getAutonomyLevel(FIXED_SESSION_ID)).thenReturn(null);
        when(agentStore.loadLatestAutonomyForSession(FIXED_SESSION_ID)).thenReturn(Optional.of(AutonomyLevel.FULL));

        handler.handle(context);

        assertEquals(FIXED_SESSION_ID, context.getSessionId().orElse(null));
        assertEquals(AutonomyLevel.FULL, context.getAutonomyLevel().orElse(null));
    }

    @Test
    void testSessionResolutionWithCheckpointFallback() {
        ChannelUser user = new ChannelUser("u1", "chat1", "User 1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "test", "chat1", Instant.now());
        MessageContext context = new MessageContext("chat1", user, msg);

        when(heartbeatTracker.getAutonomyLevel(FIXED_SESSION_ID)).thenReturn(null);
        when(agentStore.loadLatestAutonomyForSession(FIXED_SESSION_ID)).thenReturn(Optional.of(AutonomyLevel.READONLY));

        handler.handle(context);

        assertEquals(AutonomyLevel.READONLY, context.getAutonomyLevel().orElse(null));
    }

    @Test
    void testSessionResolutionDefaultsToSupervised() {
        ChannelUser user = new ChannelUser("u1", "chat1", "User 1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "test", "chat1", Instant.now());
        MessageContext context = new MessageContext("chat1", user, msg);

        when(heartbeatTracker.getAutonomyLevel(FIXED_SESSION_ID)).thenReturn(null);
        when(agentStore.loadLatestAutonomyForSession(FIXED_SESSION_ID)).thenReturn(Optional.empty());

        handler.handle(context);

        assertEquals(AutonomyLevel.SUPERVISED, context.getAutonomyLevel().orElse(null));
    }

    @Test
    void testSessionIdComesFromResolver() {
        ChannelUser user = new ChannelUser("u1", "chat1", "User 1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "test", "chat1", Instant.now());
        MessageContext context = new MessageContext("chat1", user, msg);

        when(heartbeatTracker.getAutonomyLevel(FIXED_SESSION_ID)).thenReturn(AutonomyLevel.SUPERVISED);

        handler.handle(context);

        // Session ID must come from the resolver, not from MD5/UUID v3
        String sessionId = context.getSessionId().orElse(null);
        assertNotNull(sessionId);
        assertEquals(FIXED_SESSION_ID, sessionId);
    }
}
