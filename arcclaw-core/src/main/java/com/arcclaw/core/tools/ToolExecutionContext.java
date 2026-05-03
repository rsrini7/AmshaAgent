package com.arcclaw.core.tools;

import com.arcclaw.core.scope.WorkspaceContext;
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
) {
    public static ToolExecutionContext fromContext() {
        return new ToolExecutionContext(
            WorkspaceContext.SESSION_ID.isBound() ? WorkspaceContext.SESSION_ID.get() : "legacy-session",
            WorkspaceContext.USER_ID.isBound() ? WorkspaceContext.USER_ID.get() : "legacy-user",
            WorkspaceContext.CHAT_ID.isBound() ? WorkspaceContext.CHAT_ID.get() : "legacy-chat",
            WorkspaceContext.AGENT_ROLE.isBound() ? WorkspaceContext.AGENT_ROLE.get() : "coordinator",
            WorkspaceContext.requireAutonomyOrSupervised(),
            null, // traceId
            WorkspaceContext.CHANNEL_TYPE.isBound() ? WorkspaceContext.CHANNEL_TYPE.get() : null,
            null, // platformId
            null, // goal
            null, null, null
        );
    }
}
