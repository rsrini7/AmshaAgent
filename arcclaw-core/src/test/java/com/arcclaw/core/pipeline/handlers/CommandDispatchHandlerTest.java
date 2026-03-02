package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.ApproveReflex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class CommandDispatchHandlerTest {

    private CommandDispatchHandler handler;
    private ApproveReflex approveReflex;
    private ChannelSender sender;

    @BeforeEach
    void setup() {
        approveReflex = mock(ApproveReflex.class);
        sender = mock(ChannelSender.class);
        handler = new CommandDispatchHandler();
        handler.approveReflex = approveReflex;
    }

    @Test
    void testApproveWithCronIdStopsPipeline() {
        ChannelUser user = new ChannelUser("1", "user", com.arcclaw.channel.api.ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve cron123", "sid", java.time.Instant.now());
        MessageContext ctx = createCtx(user, msg);
        
        when(approveReflex.execute("chat1", "1", "/approve cron123")).thenReturn("Approving cron task...");

        assertEquals(HandleResult.STOP, handler.handle(ctx));
        verify(sender).sendRaw(user, "Approving cron task...");
    }

    @Test
    void testApproveWithoutCronIdStopsPipeline() {
        ChannelUser user = new ChannelUser("1", "user", com.arcclaw.channel.api.ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve", "sid", java.time.Instant.now());
        MessageContext ctx = createCtx(user, msg);
        
        when(approveReflex.execute("chat1", "1", "/approve")).thenReturn("Approving session...");

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
    void testGenericCommandPassesThroughForNow() {
        ChannelUser user = new ChannelUser("1", "user", com.arcclaw.channel.api.ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/deny", "sid", java.time.Instant.now());
        MessageContext ctx = createCtx(user, msg);

        assertEquals(HandleResult.CONTINUE, handler.handle(ctx));
        verify(sender, never()).sendRaw(any(), any());
    }

    private MessageContext createCtx(ChannelUser user, ChannelMessage msg) {
        return new MessageContext("chat1", user, msg, sender);
    }
}
