package com.amsha.channel.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.ReplyNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebChannelSenderTest {

    @Mock
    WebSocketSessionStore sessionStore;

    @Mock
    WebReplyRenderer renderer;

    @Mock
    HtmlSanitizerService htmlSanitizer;

    @InjectMocks
    WebChannelSender sender;

    @BeforeEach
    void setUp() {
        when(htmlSanitizer.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(renderer.render(anyList())).thenReturn("Rendered text");
    }

    @Test
    void testSupports() {
        assertEquals(ChannelType.WEBSOCKET, sender.supports());
    }

    @Test
    void testSendRawNoSession() {
        ChannelUser user = new ChannelUser("usr1", "ws-plat-1", "user", ChannelType.WEBSOCKET);
        when(sessionStore.get("usr1")).thenReturn(Optional.empty());
        when(sessionStore.getAmshaSessionId("usr1")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> sender.sendRaw(user, "Hello text"));
    }

    @Test
    void testSendBySessionNoConnections() {
        when(sessionStore.getConnectionsByAmshaSession("sess-123")).thenReturn(List.of());

        assertDoesNotThrow(() -> sender.sendBySession("sess-123", "Broadcast text"));
    }

    @Test
    void testSendWithApprovalPrompt() {
        ChannelUser user = new ChannelUser("usr1", "ws-plat-1", "user", ChannelType.WEBSOCKET);
        ApprovalPrompt prompt = new ApprovalPrompt("sess-123", "toolX", "{\"a\":1}");
        List<ReplyNode> nodes = List.of(prompt);

        when(sessionStore.get("usr1")).thenReturn(Optional.empty());
        when(sessionStore.getAmshaSessionId("usr1")).thenReturn(Optional.of("sess-123"));
        when(sessionStore.getConnectionsByAmshaSession("sess-123")).thenReturn(List.of());

        assertDoesNotThrow(() -> sender.send(user, nodes));
    }
}
