package com.arcclaw.core.pipeline.handlers;

import static com.arcclaw.core.message.UserMessages.CLASSIFICATION_RATE_LIMITED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.exception.HttpException;

import java.time.Instant;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.IntentDetector;
import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.loop.AgentRoleCache;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.security.DestructiveVocabularyFilter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntentClassificationHandlerTest {

    private IntentClassificationHandler handler;
    private IntentDetector classifier;
    private ChannelSender sender;
    private ChannelPluginRegistry registry;
    private AgentRoleCache roleCache;
    private IntentClassificationService intentDetector;

    @BeforeEach
    void setUp() {
        handler = new IntentClassificationHandler();
        classifier = mock(IntentDetector.class);
        sender = mock(ChannelSender.class);
        registry = mock(ChannelPluginRegistry.class);
        roleCache = mock(AgentRoleCache.class);
        intentDetector = mock(IntentClassificationService.class);

        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(registry.find(any(ChannelType.class))).thenReturn(Optional.of(plugin));

        handler.classifier = classifier;
        handler.channelPluginRegistry = registry;
        handler.roleCache = roleCache;
        handler.intentDetector = intentDetector;

        when(intentDetector.isFollowUpHeuristic(anyString())).thenReturn(false);

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

        when(roleCache.findLatestRole(anyString())).thenReturn(Optional.empty());
        when(classifier.classify(anyString())).thenThrow(new HttpException(429, "Too many requests"));

        HandleResult result = handler.handle(context);

        assertEquals(HandleResult.CONTINUE, result);
        assertEquals(
                AgentRoleEnum.COORDINATOR.toScopedName(), context.getAgentRole().get());
        verify(sender).sendRaw(eq(user), eq(CLASSIFICATION_RATE_LIMITED));
    }

    @Test
    void testHandle_success_setsRole() {
        ChannelUser user = new ChannelUser("u1", "u1", "user", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", "c1", Instant.now());
        MessageContext context = new MessageContext("c1", user, msg);

        when(classifier.classify(anyString())).thenReturn(AgentRoleEnum.COORDINATOR.toScopedName());

        handler.handle(context);

        assertEquals(
                AgentRoleEnum.COORDINATOR.toScopedName(), context.getAgentRole().get());
    }

    @Test
    void testHandle_stripsTrailingPunctuation() {
        ChannelUser user = new ChannelUser("u1", "u1", "user", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "who am i /", "c1", Instant.now());
        MessageContext context = new MessageContext("c1", user, msg);

        when(classifier.classify("who am i")).thenReturn("coordinator");

        handler.handle(context);

        verify(classifier).classify("who am i");
    }
}
