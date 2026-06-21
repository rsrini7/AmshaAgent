package com.amsha.core.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.channel.ChannelPluginRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessagePipelineTest {

    private MessagePipeline pipeline;
    private MessageContext context;
    private ChannelPluginRegistry registry;
    private ChannelSender sender;

    @BeforeEach
    void setup() {
        ChannelUser user = new ChannelUser("123", "123", "alice", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "hello", "sid", Instant.now());
        sender = mock(ChannelSender.class);
        registry = mock(ChannelPluginRegistry.class);

        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(registry.find(any(ChannelType.class))).thenReturn(Optional.of(plugin));

        context = new MessageContext("chat1", user, msg);
    }

    @Test
    void testHandlersRunInPriorityOrder() {
        MessageHandler h1 = mockHandler(10, HandleResult.CONTINUE);
        MessageHandler h2 = mockHandler(20, HandleResult.CONTINUE);

        setupPipeline(h2, h1); // Add out of order

        pipeline.process(context);

        var inOrder = inOrder(h1, h2);
        inOrder.verify(h1).handle(context);
        inOrder.verify(h2).handle(context);
    }

    @Test
    void testPipelineStopsOnStopResult() {
        MessageHandler h1 = mockHandler(10, HandleResult.STOP);
        MessageHandler h2 = mockHandler(20, HandleResult.CONTINUE);

        setupPipeline(h1, h2);
        pipeline.process(context);

        verify(h1).handle(context);
        verify(h2, never()).handle(context);
    }

    @Test
    void testPipelineSwallowsExceptionAndStops() {
        MessageHandler h1 = mock(MessageHandler.class);
        when(h1.priority()).thenReturn(10);
        when(h1.handle(any())).thenThrow(new RuntimeException("Oops"));

        MessageHandler h2 = mockHandler(20, HandleResult.CONTINUE);

        setupPipeline(h1, h2);

        assertDoesNotThrow(() -> pipeline.process(context));
        verify(h2, never()).handle(context);
        verify(sender).sendRaw(eq(context.getUser()), contains("error"));
    }

    @Test
    void testEmptyPipeline() {
        setupPipeline();
        assertDoesNotThrow(() -> pipeline.process(context));
    }

    @Test
    void testMessageContextStatePropagation() {
        MessageHandler h1 = new MessageHandler() {
            public int priority() {
                return 10;
            }

            public HandleResult handle(MessageContext ctx) {
                ctx.setSessionId("session-xyz");
                return HandleResult.CONTINUE;
            }
        };

        setupPipeline(h1);
        pipeline.process(context);

        assertEquals("session-xyz", context.getSessionId().get());
    }

    @Test
    void testStopAtLastHandler() {
        MessageHandler h1 = mockHandler(10, HandleResult.CONTINUE);
        MessageHandler h2 = mockHandler(20, HandleResult.STOP);

        setupPipeline(h1, h2);
        pipeline.process(context);

        verify(h1).handle(context);
        verify(h2).handle(context);
    }

    @Test
    void testMultipleContinueHandlers() {
        MessageHandler h1 = mockHandler(10, HandleResult.CONTINUE);
        MessageHandler h2 = mockHandler(20, HandleResult.CONTINUE);
        MessageHandler h3 = mockHandler(30, HandleResult.CONTINUE);

        setupPipeline(h1, h2, h3);
        pipeline.process(context);

        verify(h1).handle(context);
        verify(h2).handle(context);
        verify(h3).handle(context);
    }

    private MessageHandler mockHandler(int priority, HandleResult result) {
        MessageHandler h = mock(MessageHandler.class);
        when(h.priority()).thenReturn(priority);
        when(h.handle(any())).thenReturn(result);
        return h;
    }

    @SuppressWarnings("unchecked")
    private void setupPipeline(MessageHandler... handlers) {
        Instance<MessageHandler> instance = mock(Instance.class);
        when(instance.stream()).thenReturn(Stream.of(handlers));
        pipeline = new MessagePipeline(instance, registry);
    }
}
