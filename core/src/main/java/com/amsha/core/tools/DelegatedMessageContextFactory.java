package com.amsha.core.tools;

import java.time.Instant;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.AutonomyLevel;

/** Builds the channel-aware context used by delegated agent executions. */
final class DelegatedMessageContextFactory {
    private DelegatedMessageContextFactory() {}

    static MessageContext create(
            String sessionId,
            String userId,
            String chatId,
            String channelType,
            String goal,
            String role,
            AutonomyLevel autonomy) {
        String effectiveUserId = nonBlankOr(userId, "unknown");
        String effectiveChatId = nonBlankOr(chatId, sessionId);
        ChannelType effectiveChannel = resolveChannelType(channelType);
        ChannelUser user = new ChannelUser(effectiveUserId, effectiveChatId, "Delegated operator", effectiveChannel);
        ChannelMessage message =
                new ChannelMessage.GenericChannelMessage(user, goal, effectiveChatId, Instant.now(), effectiveChannel);
        MessageContext context = new MessageContext(effectiveChatId, user, message);
        context.setSessionId(sessionId);
        context.setAgentRole(role);
        context.setGoal(goal);
        context.setAutonomyLevel(autonomy);
        return context;
    }

    private static ChannelType resolveChannelType(String channelType) {
        String effectiveChannelType = channelType;
        if (effectiveChannelType == null || effectiveChannelType.isBlank()) {
            effectiveChannelType = WorkspaceContext.channelType().orElse(ChannelType.TELEGRAM.id());
        }
        return ChannelType.of(effectiveChannelType);
    }

    private static String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
