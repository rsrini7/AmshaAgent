package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommandDispatchHandlerTest {

    private CommandDispatchHandler handler;
    private ReflexRegistry reflexRegistry;
    private ChannelSender sender;

    @BeforeEach
    void setup() {
        reflexRegistry = mock(ReflexRegistry.class);
        sender = mock(ChannelSender.class);
        handler = new CommandDispatchHandler();
        handler.reflexRegistry = reflexRegistry;
    }

    @Test
    void testApproveWithCronIdStopsPipeline() {
        ChannelUser user = new ChannelUser("1", "user", com.arcclaw.channel.api.ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve cron123", "sid", java.time.Instant.now());
        MessageContext ctx = createCtx(user, msg);
        
        when(reflexRegistry.processIfReflex(anyString(), eq("1"), anyString(), eq("/approve cron123")))
                .thenReturn(new ReflexResult(Optional.of("Approving cron task..."), "/approve cron123"));

        assertEquals(HandleResult.STOP, handler.handle(ctx));
        verify(sender).sendRaw(user, "Approving cron task...");
    }

    @Test
    void testApproveWithoutCronIdStopsPipeline() {
        ChannelUser user = new ChannelUser("1", "user", com.arcclaw.channel.api.ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve", "sid", java.time.Instant.now());
        MessageContext ctx = createCtx(user, msg);
        
        when(reflexRegistry.processIfReflex(anyString(), eq("1"), anyString(), eq("/approve")))
                .thenReturn(new ReflexResult(Optional.of("Approving session..."), "/approve"));

        assertEquals(HandleResult.STOP, handler.handle(ctx));
        verify(sender).sendRaw(user, "Approving session...");
    }

    @Test
    void testTextPassesThrough() {
        ChannelUser user = new ChannelUser("1", "user", com.arcclaw.channel.api.ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "Hello world", "sid", java.time.Instant.now());
        MessageContext ctx = createCtx(user, msg);

        assertEquals(HandleResult.CONTINUE, handler.handle(ctx));
        verify(sender, never()).sendRaw(any(), any());
    }

    @Test
    void testDenyStopsPipeline() {
        ChannelUser user = new ChannelUser("1", "user", com.arcclaw.channel.api.ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/deny", "sid", java.time.Instant.now());
        MessageContext ctx = createCtx(user, msg);

        when(reflexRegistry.processIfReflex(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ReflexResult(Optional.empty(), "/deny"));

        assertEquals(HandleResult.STOP, handler.handle(ctx));
        verify(sender, never()).sendRaw(any(), any());
    }

    private MessageContext createCtx(ChannelUser user, ChannelMessage msg) {
        return new MessageContext("chat1", user, msg, sender);
    }
}
