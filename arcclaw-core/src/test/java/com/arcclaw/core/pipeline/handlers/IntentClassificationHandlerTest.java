package com.arcclaw.core.pipeline.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.IntentClassifier;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.security.DestructiveVocabularyFilter;
import dev.langchain4j.exception.HttpException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntentClassificationHandlerTest {

    private IntentClassificationHandler handler;
    private IntentClassifier classifier;
    private ChannelSender sender;
    private ChannelPluginRegistry registry;

    @BeforeEach
    void setUp() {
        handler = new IntentClassificationHandler();
        classifier = mock(IntentClassifier.class);
        sender = mock(ChannelSender.class);
        registry = mock(ChannelPluginRegistry.class);

        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(registry.find(any(ChannelType.class))).thenReturn(Optional.of(plugin));

        handler.classifier = classifier;
        handler.channelPluginRegistry = registry;

        var destructiveFilter = mock(DestructiveVocabularyFilter.class);
        when(destructiveFilter.isDestructive(anyString())).thenReturn(false);
        when(destructiveFilter.filter(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0)); // ← THIS was missing
        handler.destructiveVocabularyFilter = destructiveFilter;
    }


    @Test
    void testHandle_rateLimit_notifiesUserAndDefaultsToCoordinator() {
        ChannelUser user = new ChannelUser("u1", "u1", "user", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", "c1", Instant.now());
        MessageContext context = new MessageContext("c1", user, msg);

        when(classifier.classify(anyString())).thenThrow(new HttpException(429, "Too many requests"));

        HandleResult result = handler.handle(context);

        assertEquals(HandleResult.CONTINUE, result);
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), context.getAgentRole().get());
        verify(sender).sendRaw(eq(user), eq("⏳ Rate limited on classification — defaulting to coordinator."));
    }

    @Test
    void testHandle_success_setsRole() {
        ChannelUser user = new ChannelUser("u1", "u1", "user", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", "c1", Instant.now());
        MessageContext context = new MessageContext("c1", user, msg);

        // "developer" is not a valid AgentRoleEnum — use a real scoped name
        when(classifier.classify(anyString()))
                .thenReturn(AgentRoleEnum.CODER.toScopedName());

        handler.handle(context);

        assertEquals(AgentRoleEnum.CODER.toScopedName(), context.getAgentRole().get());
    }

}
