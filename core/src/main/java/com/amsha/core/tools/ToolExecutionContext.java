package com.amsha.core.tools;

import com.amsha.core.security.AutonomyLevel;

/**
 * Contextual information for tool execution.
 * Passed to {@link AgentTool#execute(ToolExecutionContext, java.util.Map)}.
 */
public record ToolExecutionContext(
        String sessionId,
        String userId,
        String chatId,
        String agentRole,
        AutonomyLevel autonomyLevel,
        String traceId,
        String channelType,
        String channelPlatformId,
        String rawUserGoal,
        String suggestedCron,
        String suggestedNextFireTime,
        String suggestedCronSource,
        String skillHome,
        boolean browserPreview) {

    public ToolExecutionContext(
            String sessionId,
            String userId,
            String chatId,
            String agentRole,
            AutonomyLevel autonomyLevel,
            String traceId,
            String channelType,
            String channelPlatformId,
            String rawUserGoal,
            String suggestedCron,
            String suggestedNextFireTime,
            String suggestedCronSource) {
        this(
                sessionId,
                userId,
                chatId,
                agentRole,
                autonomyLevel,
                traceId,
                channelType,
                channelPlatformId,
                rawUserGoal,
                suggestedCron,
                suggestedNextFireTime,
                suggestedCronSource,
                null,
                false);
    }

    /** Compatibility constructor for callers that already supplied skillHome. */
    public ToolExecutionContext(
            String sessionId, String userId, String chatId, String agentRole, AutonomyLevel autonomyLevel,
            String traceId, String channelType, String channelPlatformId, String rawUserGoal,
            String suggestedCron, String suggestedNextFireTime, String suggestedCronSource, String skillHome) {
        this(sessionId, userId, chatId, agentRole, autonomyLevel, traceId, channelType, channelPlatformId,
                rawUserGoal, suggestedCron, suggestedNextFireTime, suggestedCronSource, skillHome, false);
    }
}
