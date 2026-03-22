package com.arcclaw.app.channel.telegram;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessagePipeline;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutorService;
import java.lang.reflect.Field;
import com.github.benmanes.caffeine.cache.Cache;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ChannelType;
import java.time.Instant;

class TelegramAgentHandlerTest {

    private TelegramAgentHandler handler;
    private MessagePipeline pipeline;
    private ManagedExecutor executor;
    private TelegramMessageMapper mapper;
    private TelegramChannelSender sender;

    @BeforeEach
    void setup() throws Exception {
        pipeline = mock(MessagePipeline.class);
        executor = mock(ManagedExecutor.class);
        mapper = mock(TelegramMessageMapper.class);
        sender = mock(TelegramChannelSender.class);

        handler = new TelegramAgentHandler();
        handler.pipeline = pipeline;
        handler.executor = executor;
        handler.messageMapper = mapper;
        handler.sender = sender;

        // Stub a mock executor to run immediately in the same thread
        ExecutorService syncExecutor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(syncExecutor).submit(any(Runnable.class));

        // Inject the sync executor into the chatQueues cache via reflection
        Field queuesField = TelegramAgentHandler.class.getDeclaredField("chatQueues");
        queuesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Cache<String, ExecutorService> mockCache = 
            mock(Cache.class);
        when(mockCache.get(anyString(), any())).thenReturn(syncExecutor);
        queuesField.set(handler, mockCache);
    }

    @Test
    void testValidMessageDelegatesToPipeline() {
        Exchange exchange = createExchange("chat123", "456", "alice", "hello world");
        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                new ChannelUser("456", "alice", ChannelType.TELEGRAM), "hello world", "chat123", Instant.now());
        when(mapper.map(eq("hello world"), any(), eq("chat123"))).thenReturn(msg);

        handler.receiveMessage(exchange);

        ArgumentCaptor<MessageContext> captor = ArgumentCaptor.forClass(MessageContext.class);
        verify(pipeline).process(captor.capture());

        MessageContext ctx = captor.getValue();
        assertEquals("chat123", ctx.getChatId());
        assertEquals("456", ctx.getUser().platformId());
        assertEquals("alice", ctx.getUser().displayName());
        assertInstanceOf(ChannelMessage.TelegramMessage.class, ctx.getMessage());
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
        org.apache.camel.Message in = mock(org.apache.camel.Message.class);
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
                new ChannelUser("456", "alice", ChannelType.TELEGRAM), "valid text", "chat123", Instant.now());
        when(mapper.map(eq("valid text"), any(), eq("chat123"))).thenReturn(msg);

        handler.receiveMessage(exchange);

        verify(pipeline).process(any());
        verify(sender, never()).sendRaw(any(), contains("not supported"));
    }

    @Test
    void testExceptionInPipelineIsHandledGracefully() {
        Exchange exchange = createExchange("chat123", "456", "alice", "hello world");
        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                new ChannelUser("456", "alice", ChannelType.TELEGRAM), "hello world", "chat123", Instant.now());
        when(mapper.map(eq("hello world"), any(), eq("chat123"))).thenReturn(msg);
        doThrow(new RuntimeException("Pipeline crash")).when(pipeline).process(any());

        assertDoesNotThrow(() -> handler.receiveMessage(exchange));
        
        // Verify notification was sent
        verify(sender).sendRaw(argThat(user -> "456".equals(user.platformId())), contains("internal error occurred"));
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
