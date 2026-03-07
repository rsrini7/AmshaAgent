package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.channel.api.ChannelAllowlist;
import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AllowlistHandlerTest {

    private AllowlistHandler handler;
    private ChannelAllowlist allowlist;
    private ChannelSender sender;
    private MessageContext context;

    @BeforeEach
    void setup() {
        allowlist = mock(ChannelAllowlist.class);
        sender = mock(ChannelSender.class);
        handler = new AllowlistHandler();
        handler.allowlist = allowlist;

        ChannelUser user = new ChannelUser("123", "alice", ChannelType.CLI);
        context = new MessageContext("chat1", user, new ChannelMessage.CliMessage(user, "hi", "sid", Instant.now()), sender);
    }

    @Test
    void testAllowedUserReturnsContinue() {
        when(allowlist.isAllowed("123", "alice")).thenReturn(true);
        
        assertEquals(HandleResult.CONTINUE, handler.handle(context));
        verify(sender, never()).send(any(), any());
    }

    @Test
    void testBlockedUserSendsResponseAndReturnsStop() {
        when(allowlist.isAllowed("123", "alice")).thenReturn(false);
        when(allowlist.isSilentBlock()).thenReturn(false);
        when(allowlist.getBlockResponse()).thenReturn("Blocked!");

        assertEquals(HandleResult.STOP, handler.handle(context));
        verify(sender).sendRaw(any(), eq("Blocked!"));
    }

    @Test
    void testSilentBlockUserReturnsStopWithoutSending() {
        when(allowlist.isAllowed("123", "alice")).thenReturn(false);
        when(allowlist.isSilentBlock()).thenReturn(true);

        assertEquals(HandleResult.STOP, handler.handle(context));
        verify(sender, never()).send(any(), any());
    }
}
