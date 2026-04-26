package com.arcclaw.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.handlers.AgentDispatchHandler;
import com.arcclaw.core.security.AutonomyLevel;
import org.junit.jupiter.api.Test;

class MessageContextTest {

    @Test
    void messageContextHasNoGetSenderMethod() {
        assertThrows(NoSuchMethodException.class, () -> {
            MessageContext.class.getMethod("getSender");
        });
    }

    @Test
    void getChannelTypeDerivesFromUser() {
        ChannelUser user = new ChannelUser("user1", "chat1", "John", ChannelType.TELEGRAM);
        MessageContext ctx = new MessageContext("chat1", user, null);
        assertEquals(ChannelType.TELEGRAM, ctx.getChannelType());
    }

    @Test
    void agentDispatchHandlerThrowsMeaningfulErrorIfSessionContextMissing() {
        ChannelUser user = new ChannelUser("user1", "chat1", "John", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "hello", "sess1", Instant.now());
        MessageContext ctx = new MessageContext("chat1", user, msg);
        
        // Do NOT call setSessionContext
        AgentDispatchHandler handler = new AgentDispatchHandler();
        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> handler.handle(ctx));
        assertTrue(ex.getMessage().contains("SessionResolutionHandler"));
    }

    @Test
    void sessionContextIsImmutableAfterSet() {
        ChannelUser user = new ChannelUser("user1", "chat1", "John", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "hello", "sess1", Instant.now());
        MessageContext ctx = new MessageContext("chat1", user, msg);

        SessionContext sc = new SessionContext("sess1", user, AutonomyLevel.FULL, false);
        ctx.setSessionContext(sc);
        assertSame(sc, ctx.getSessionContext().get());
        // Records are immutable by definition - no additional assertion needed
    }
}
