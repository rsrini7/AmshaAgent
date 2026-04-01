package com.arcclaw.channel.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessagePipeline;
import io.quarkus.test.InjectMock;
import io.quarkus.test.component.QuarkusComponentTest;
import org.junit.jupiter.api.Test;

/**
 * Package-private test exception to verify suppression removal.
 */
class PipelineCrashException extends RuntimeException {
    PipelineCrashException(String message) {
        super(message);
    }
}

@QuarkusComponentTest
class TelegramAgentHandlerPipelineSentinelTest {

    @Inject
    TelegramAgentHandler handler;

    @InjectMock
    MessagePipeline pipeline;

    @InjectMock
    TelegramChannelSender sender;

    @Test
    void bugA10_suppressionGone_notifiesUserOnSentinelMessage() {
        String chatId = "chat123";
        ChannelUser user = new ChannelUser("user456", chatId, "alice", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", chatId, Instant.now());
        MessageContext context = new MessageContext(chatId, user, msg);

        // This used to be swallowed
        doThrow(new RuntimeException("Pipeline crash")).when(pipeline).process(any());

        handler.processPipeline(context);

        // Verify user IS notified now
        verify(sender).sendRaw(eq(user), contains("internal error occurred"));
    }

    @Test
    void bugA10_normalExceptionPath_notifiesUser() {
        String chatId = "chat789";
        ChannelUser user = new ChannelUser("user000", chatId, "bob", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", chatId, Instant.now());
        MessageContext context = new MessageContext(chatId, user, msg);

        doThrow(new RuntimeException("DB connection lost")).when(pipeline).process(any());

        handler.processPipeline(context);

        // Verify user IS notified
        verify(sender).sendRaw(eq(user), contains("internal error occurred"));
    }

    @Test
    void bugA10_customExceptionClasses_notifiesUser() {
        String chatId = "chat111";
        ChannelUser user = new ChannelUser("user222", chatId, "charlie", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", chatId, Instant.now());
        MessageContext context = new MessageContext(chatId, user, msg);

        // Test-only exception class
        doThrow(new PipelineCrashException("Custom sentinel crash")).when(pipeline).process(any());

        handler.processPipeline(context);

        // Verify user IS notified
        verify(sender).sendRaw(eq(user), contains("internal error occurred"));
    }
}
