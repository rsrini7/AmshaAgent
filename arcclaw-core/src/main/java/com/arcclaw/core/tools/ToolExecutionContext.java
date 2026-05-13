package com.arcclaw.core.tools;

import com.arcclaw.core.security.AutonomyLevel;

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
    String suggestedCronSource
) {}
