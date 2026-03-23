package com.arcclaw.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessagePipelineErrorTest {

    private MessagePipeline pipeline;
    private MessageContext context;
    private ChannelSender sender;
    private ChannelUser user;

    @BeforeEach
    void setup() {
        user = new ChannelUser("123", "alice", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "hello", "sid", Instant.now());
        sender = mock(ChannelSender.class);
        context = new MessageContext("chat1", user, msg, sender);
    }

    @Test
    void testHandlerCrashNotifiesUser() {
        MessageHandler crashingHandler = mock(MessageHandler.class);
        when(crashingHandler.priority()).thenReturn(10);
        when(crashingHandler.handle(any())).thenThrow(new RuntimeException("Handler crash!"));

        setupPipeline(crashingHandler);

        pipeline.process(context);

        // Verify notification sent to user
        verify(sender).sendRaw(eq(user), contains("internal error"));
    }

    @Test
    void testPipelineContinuesNotificationAfterHandlerCrash() {
        MessageHandler crashingHandler = mock(MessageHandler.class);
        when(crashingHandler.priority()).thenReturn(10);
        when(crashingHandler.handle(any())).thenThrow(new RuntimeException("Handler crash!"));

        setupPipeline(crashingHandler);

        // Should not throw exception to caller
        assertDoesNotThrow(() -> pipeline.process(context));
    }

    @Test
    void testNotificationFailureDoesNotPropagate() {
        MessageHandler crashingHandler = mock(MessageHandler.class);
        when(crashingHandler.priority()).thenReturn(10);
        when(crashingHandler.handle(any())).thenThrow(new RuntimeException("Handler crash!"));

        // Make sender throw during notification
        doThrow(new RuntimeException("Sender failed")).when(sender).sendRaw(any(), any());

        setupPipeline(crashingHandler);

        // Should still not throw
        assertDoesNotThrow(() -> pipeline.process(context));
    }

    @SuppressWarnings("unchecked")
    private void setupPipeline(MessageHandler... handlers) {
        Instance<MessageHandler> instance = mock(Instance.class);
        when(instance.stream()).thenReturn(Stream.of(handlers));
        pipeline = new MessagePipeline(instance);
    }
}
