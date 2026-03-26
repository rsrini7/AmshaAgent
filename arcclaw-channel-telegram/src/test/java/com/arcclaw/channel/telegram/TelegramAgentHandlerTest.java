package com.arcclaw.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessagePipeline;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.InjectMock;
import io.quarkus.test.component.QuarkusComponentTest;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@QuarkusComponentTest
class TelegramAgentHandlerTest {

    @Inject
    TelegramAgentHandler handler;

    @InjectMock
    MessagePipeline pipeline;

    @InjectMock
    ManagedExecutor executor;

    @InjectMock
    TelegramMessageMapper mapper;

    @InjectMock
    TelegramChannelSender sender;

    @BeforeEach
    void setup() throws Exception {
        // Stub a mock executor to run immediately in the same thread
        ExecutorService syncExecutor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(syncExecutor).submit(any(Runnable.class));

        // Inject the sync executor into the chatQueues cache via reflection
        // Since handler is a proxy, we must unwrap it
        TelegramAgentHandler target = ClientProxy.unwrap(handler);
        
        // 1. Stub the processingFlags to always return a fresh ready flag
        Field flagsField = TelegramAgentHandler.class.getDeclaredField("processingFlags");
        flagsField.setAccessible(true);
        Cache<String, AtomicBoolean> flags = Caffeine.newBuilder().build();
        flagsField.set(target, flags);

        // 2. Stub the ManagedExecutor to run synchronously
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
    }

    @Test
    void testValidMessageDelegatesToPipeline() {
        Exchange exchange = createExchange("chat123", "456", "alice", "hello world");
        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                new ChannelUser("456", "456", "alice", ChannelType.TELEGRAM), "hello world", "chat123", Instant.now());
        when(mapper.map(eq("hello world"), any(), eq("chat123"))).thenReturn(msg);

        handler.receiveMessage(exchange);

        ArgumentCaptor<MessageContext> captor = ArgumentCaptor.forClass(MessageContext.class);
        verify(pipeline).process(captor.capture());

        MessageContext ctx = captor.getValue();
        assertEquals("chat123", ctx.getChatId());
        assertEquals("456", ctx.getUser().platformId());
        assertEquals("chat123", ctx.getUser().channelId());
        assertEquals("alice", ctx.getUser().displayName());
        assertInstanceOf(ChannelMessage.TelegramMessage.class, ctx.getMessage());
    }

    @Test
    void testTelegramChannelUserChannelIdEqualsChatId() {
        Exchange exchange = createExchange("groupChat999", "123", "bob", "hello");
        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                new ChannelUser("123", "groupChat999", "bob", ChannelType.TELEGRAM), "hello", "groupChat999", Instant.now());
        when(mapper.map(eq("hello"), any(), eq("groupChat999"))).thenReturn(msg);

        handler.receiveMessage(exchange);

        ArgumentCaptor<MessageContext> captor = ArgumentCaptor.forClass(MessageContext.class);
        verify(pipeline).process(captor.capture());

        MessageContext ctx = captor.getValue();
        assertEquals("123", ctx.getUser().platformId());
        assertEquals("groupChat999", ctx.getUser().channelId(), "Channel ID must equal Chat ID");
    }

    @Test
    void testNonTextMessageNotifiesUser() {
        Exchange exchange = createExchange("chat123", "456", "alice", null);
        
        handler.receiveMessage(exchange);

        verify(pipeline, never()).process(any());
        verify(sender).sendRaw(argThat(user -> "456".equals(user.platformId())), contains("not supported"));
    }

    @Test
    void testNullMessageBodyIsHandledGracefully() {
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        when(exchange.getIn()).thenReturn(in);
        when(in.getBody(IncomingMessage.class)).thenReturn(null);

        assertDoesNotThrow(() -> handler.receiveMessage(exchange));

        verify(pipeline, never()).process(any());
        verify(sender, never()).sendRaw(any(), any());
    }

    @Test
    void testTextMessageProcessedNormally() {
        Exchange exchange = createExchange("chat123", "456", "alice", "valid text");
        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                new ChannelUser("456", "456", "alice", ChannelType.TELEGRAM), "valid text", "chat123", Instant.now());
        when(mapper.map(eq("valid text"), any(), eq("chat123"))).thenReturn(msg);

        handler.receiveMessage(exchange);

        verify(pipeline).process(any());
        verify(sender, never()).sendRaw(any(), contains("not supported"));
    }

    @Test
    void testExceptionInPipelineIsHandledGracefully() {
        Exchange exchange = createExchange("chat123", "456", "alice", "hello world");
        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                new ChannelUser("456", "456", "alice", ChannelType.TELEGRAM), "hello world", "chat123", Instant.now());
        when(mapper.map(eq("hello world"), any(), eq("chat123"))).thenReturn(msg);
        doThrow(new RuntimeException("Real crash")).when(pipeline).process(any());

        assertDoesNotThrow(() -> handler.receiveMessage(exchange));
        
        // Verify notification was sent (ignoring the initial ⏳ acknowledgment)
        verify(sender, atLeastOnce()).sendRaw(argThat(user -> "456".equals(user.platformId())), contains("internal error occurred"));
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

    @Test
    void rapidConcurrentMessagesFromSameChatAreSerialised() throws Exception {
        String chatId = "race-chat-" + UUID.randomUUID();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstComplete = new CountDownLatch(1);

        // Configure mock pipeline: first call signals firstStarted, then awaits firstComplete
        doAnswer(invocation -> {
            firstStarted.countDown();
            assertTrue(firstComplete.await(5, TimeUnit.SECONDS), "First message took too long to complete");
            return null;
        }).when(pipeline).process(argThat(ctx -> chatId.equals(ctx.getChatId())));

        // Channel message with text
        ChannelMessage.TelegramMessage channelMsg = new ChannelMessage.TelegramMessage(
                new ChannelUser("123", chatId, "alice", ChannelType.TELEGRAM), "hello", chatId, Instant.now());
        when(mapper.map(anyString(), any(), anyString())).thenReturn(channelMsg);

        // 1. Start first message in a real background thread to simulate concurrency
        new Thread(() -> {
            Exchange ex = createExchange(chatId, "123", "alice", "hello");
            handler.receiveMessage(ex);
        }).start();

        // Wait for first message to actually start and hold the lock
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "First message never started");
        assertTrue(handler.isProcessing(chatId), "Handler should be processing the first message");

        // 2. Send second message while first is still running
        Exchange ex2 = createExchange(chatId, "123", "alice", "another hello");
        handler.receiveMessage(ex2);

        // Second message must get "still processing" reply
        verify(sender).sendRaw(argThat(user -> chatId.equals(user.channelId())), contains("Still processing"));

        // 3. Complete first message
        firstComplete.countDown();
        
        // Give it a moment to finish the finally block
        Thread.sleep(100);

        assertFalse(handler.isProcessing(chatId), "Clean state after processing completes");

        // 4. Send third message — it should be accepted normally
        Exchange ex3 = createExchange(chatId, "123", "alice", "third hello");
        handler.receiveMessage(ex3);
        verify(pipeline, atLeast(2)).process(any()); // first and third
    }

    @Test
    void processingFlagIsFalseAfterSuccessfulProcessing() {
        String chatId = "chat-ok-" + UUID.randomUUID();
        Exchange exchange = createExchange(chatId, "123", "alice", "hello ok");
        ChannelMessage.TelegramMessage channelMsg = new ChannelMessage.TelegramMessage(
                new ChannelUser("123", chatId, "alice", ChannelType.TELEGRAM), "hello ok", chatId, Instant.now());
        when(mapper.map(anyString(), any(), anyString())).thenReturn(channelMsg);

        handler.receiveMessage(exchange);

        assertFalse(handler.isProcessing(chatId), "Flag must be false after completion");
    }
}
