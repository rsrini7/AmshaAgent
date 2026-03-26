package com.arcclaw.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

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
        ConcurrentHashMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();
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
}
