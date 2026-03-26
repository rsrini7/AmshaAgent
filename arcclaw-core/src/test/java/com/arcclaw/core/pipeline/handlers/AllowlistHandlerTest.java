package com.arcclaw.core.pipeline.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelAllowlist;
import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AllowlistHandlerTest {

    private AllowlistHandler handler;
    private ChannelAllowlist allowlist;
    private ChannelSender sender;
    private MessageContext context;
    private ChannelPluginRegistry registry;

    @BeforeEach
    void setup() {
        allowlist = mock(ChannelAllowlist.class);
        sender = mock(ChannelSender.class);
        registry = mock(ChannelPluginRegistry.class);
        
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(registry.find(any(ChannelType.class))).thenReturn(Optional.of(plugin));

        handler = new AllowlistHandler();
        handler.allowlist = allowlist;
        handler.channelPluginRegistry = registry;

        ChannelUser user = new ChannelUser("123", "123", "alice", ChannelType.CLI);
        context = new MessageContext("chat1", user, new ChannelMessage.CliMessage(user, "hi", "sid", Instant.now()));
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
