package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.IntentClassifier;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class IntentClassificationHandlerTest {

    private IntentClassificationHandler handler;
    private IntentClassifier classifier;
    private MessageContext context;

    @BeforeEach
    void setup() {
        classifier = mock(IntentClassifier.class);
        handler = new IntentClassificationHandler();
        handler.classifier = classifier;
        
        ChannelUser user = new ChannelUser("123", "alice", com.arcclaw.channel.api.ChannelType.CLI);
        context = new MessageContext("chat1", user, new ChannelMessage.CliMessage(user, "need some code", "sid", java.time.Instant.now()), mock(ChannelSender.class));
    }

    @Test
    void testClassificationSetsRoleAndContinues() {
        when(classifier.classify("need some code")).thenReturn("coder");

        assertEquals(HandleResult.CONTINUE, handler.handle(context));
        assertTrue(context.getAgentRole().isPresent());
        assertEquals("coder", context.getAgentRole().get());
    }

    @Test
    void testClassificationFailureDefaultsToCoordinator() {
        when(classifier.classify(any())).thenThrow(new RuntimeException("API down"));

        assertEquals(HandleResult.CONTINUE, handler.handle(context));
        assertTrue(context.getAgentRole().isPresent());
        assertEquals("coordinator", context.getAgentRole().get());
    }
}
