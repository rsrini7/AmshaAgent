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

class TelegramAgentHandlerTest {

    private TelegramAgentHandler handler;
    private MessagePipeline pipeline;
    private ManagedExecutor executor;
    private TelegramMessageMapper mapper;
    private TelegramChannelSender sender;

    @BeforeEach
    void setup() {
        pipeline = mock(MessagePipeline.class);
        executor = mock(ManagedExecutor.class);
        mapper = mock(TelegramMessageMapper.class);
        sender = mock(TelegramChannelSender.class);

        handler = new TelegramAgentHandler();
        handler.pipeline = pipeline;
        handler.executor = executor;
        handler.messageMapper = mapper;
        handler.sender = sender;

        // Stub the executor to run immediately in the same thread
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).submit(any(Runnable.class));
    }

    @Test
    void testValidMessageDelegatesToPipeline() {
        Exchange exchange = createExchange("chat123", "456", "alice", "hello world");
        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                new com.arcclaw.channel.api.ChannelUser("456", "alice", com.arcclaw.channel.api.ChannelType.TELEGRAM), "hello world", "chat123", java.time.Instant.now());
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
    void testNullTextMessageIsDropped() {
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        when(exchange.getIn()).thenReturn(in);
        IncomingMessage msg = new IncomingMessage();
        msg.setText(null);
        when(in.getBody(IncomingMessage.class)).thenReturn(msg);

        handler.receiveMessage(exchange);

        verify(pipeline, never()).process(any());
        verify(executor, never()).submit(any(Runnable.class));
    }

    @Test
    void testExceptionInPipelineIsHandledGracefully() {
        Exchange exchange = createExchange("chat123", "456", "alice", "hello world");
        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                new com.arcclaw.channel.api.ChannelUser("456", "alice", com.arcclaw.channel.api.ChannelType.TELEGRAM), "hello world", "chat123", java.time.Instant.now());
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
