package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelServices;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.ProcessingLockManager;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.component.QuarkusComponentTest;

@QuarkusComponentTest
class TelegramAgentHandlerTest {

    @Inject
    TelegramAgentHandler handler;

    @InjectMock
    ChannelServices channelServices;

    @InjectMock
    TelegramMessageMapper mapper;

    @InjectMock
    TelegramChannelSender sender;

    @InjectMock
    ProcessingLockManager lockManager;

    /** In-memory lock state used by the ProcessingLockManager mock. */
    private final Map<String, Boolean> locks = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() throws Exception {
        locks.clear();
        // Default behavior: processAsync returns immediately
        when(channelServices.processAsync(any())).thenReturn(CompletableFuture.completedFuture(null));

        // Realistic lock behavior backed by an in-memory map
        when(lockManager.tryLock(any()))
                .thenAnswer(invocation -> locks.putIfAbsent(invocation.getArgument(0), Boolean.TRUE) == null);
        when(lockManager.isLocked(any())).thenAnswer(invocation -> locks.containsKey(invocation.getArgument(0)));
        // Use lenient() to avoid UnnecessaryStubbingException when unlock is not always called
        Mockito.lenient()
                .doAnswer(invocation -> locks.remove(invocation.getArgument(0)))
                .when(lockManager)
                .unlock(any());
    }

    @Test
    void testValidMessageDelegatesToSPI() {
        Exchange exchange = createExchange("chat123", "456", "alice", "hello world");
        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                new ChannelUser("456", "456", "alice", ChannelType.TELEGRAM), "hello world", "chat123", Instant.now());
        when(mapper.map(eq("hello world"), any(), eq("chat123"))).thenReturn(msg);

        handler.receiveMessage(exchange);

        ArgumentCaptor<ChannelMessage> captor = ArgumentCaptor.forClass(ChannelMessage.class);
        verify(channelServices).processAsync(captor.capture());

        ChannelMessage res = captor.getValue();
        assertEquals("chat123", res.correlationId());
        assertEquals("456", res.sender().platformId());
        assertEquals("alice", res.sender().displayName());
    }

    @Test
    void testNonTextMessageNotifiesUser() {
        Exchange exchange = createExchange("chat123", "456", "alice", null);

        handler.receiveMessage(exchange);

        verify(channelServices, never()).processAsync(any());
        verify(sender)
                .sendRaw(argThat(user -> "456".equals(user.platformId())), contains("only process text messages"));
    }

    @Test
    void testNullMessageBodyIsHandledGracefully() {
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        when(exchange.getIn()).thenReturn(in);
        when(in.getBody(IncomingMessage.class)).thenReturn(null);

        assertDoesNotThrow(() -> handler.receiveMessage(exchange));

        verify(channelServices, never()).processAsync(any());
        verify(sender, never()).sendRaw(any(), any());
    }

    @Test
    void testExceptionInProcessingIsHandledGracefully() {
        Exchange exchange = createExchange("chat123", "456", "alice", "hello world");
        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                new ChannelUser("456", "456", "alice", ChannelType.TELEGRAM), "hello world", "chat123", Instant.now());
        when(mapper.map(eq("hello world"), any(), eq("chat123"))).thenReturn(msg);

        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("SPI error"));
        when(channelServices.processAsync(any())).thenReturn(failed);

        // Suppress expected stack traces in the test logs
        var julLogger = Logger.getLogger(TelegramAgentHandler.class.getName());
        julLogger.setLevel(Level.OFF);
        try {
            assertDoesNotThrow(() -> handler.receiveMessage(exchange));
        } finally {
            julLogger.setLevel(null); // Restore to parent logging level configuration
        }

        verify(sender, atLeastOnce())
                .sendRaw(argThat(user -> "456".equals(user.platformId())), contains("internal error occurred"));
    }

    @Test
    void rapidConcurrentMessagesFromSameChatAreSerialised() throws Exception {
        String chatId = "chat-" + UUID.randomUUID();
        CompletableFuture<Void> pending = new CompletableFuture<>();

        // 1. Stub first message to hang
        ChannelMessage.TelegramMessage msg1 = new ChannelMessage.TelegramMessage(
                new ChannelUser("123", chatId, "alice", ChannelType.TELEGRAM), "hello", chatId, Instant.now());
        when(mapper.map(eq("hello"), any(), eq(chatId))).thenReturn(msg1);
        when(channelServices.processAsync(msg1)).thenReturn(pending);

        handler.receiveMessage(createExchange(chatId, "123", "alice", "hello"));
        assertTrue(handler.isProcessing(chatId));

        // 2. Second message while first is pending
        ChannelMessage.TelegramMessage msg2 = new ChannelMessage.TelegramMessage(
                new ChannelUser("123", chatId, "alice", ChannelType.TELEGRAM), "world", chatId, Instant.now());
        when(mapper.map(eq("world"), any(), eq(chatId))).thenReturn(msg2);

        handler.receiveMessage(createExchange(chatId, "123", "alice", "world"));
        verify(sender).sendRaw(any(), contains("Still processing"));

        // 3. Complete first
        pending.complete(null);
        assertFalse(handler.isProcessing(chatId));
    }

    private Exchange createExchange(String chatId, String userId, String username, String text) {
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        when(exchange.getIn()).thenReturn(in);
        when(in.getHeader("CamelTelegramChatId", String.class)).thenReturn(chatId);

        IncomingMessage msg = new IncomingMessage();
        User srcUser = new User();
        srcUser.setId(Long.parseLong(userId));
        srcUser.setUsername(username);
        msg.setFrom(srcUser);
        msg.setText(text);

        when(in.getBody(IncomingMessage.class)).thenReturn(msg);
        return exchange;
    }
}
