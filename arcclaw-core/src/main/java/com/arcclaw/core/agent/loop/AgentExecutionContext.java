package com.arcclaw.core.agent.loop;

import java.util.Objects;

import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.security.AutonomyLevel;

/**
 * Immutable context for an agent loop execution.
 * Encapsulates all inputs required to start or resume a loop.
 *
 * channelType and channelPlatformId are nullable — only present when
 * the loop is driven by an inbound channel message (Telegram, WebSocket, etc.).
 * They are stamped onto the checkpoint inside ApprovalCheckpointManager when
 * the agent pauses for human approval, replacing the post-persist load/save
 * cycle that previously lived in AgentLoop.tryStampChannelInfo().
 */
public record AgentExecutionContext(
        String sessionId,
        String userId,
        String chatId,
        String agentRole,
        String goal,
        AutonomyLevel autonomyLevel,
        int maxIterations,
        String channelType,        // NEW — nullable, from MessageContext
        String channelPlatformId   // NEW — nullable, from MessageContext
) {
    public AgentExecutionContext {
        Objects.requireNonNull(sessionId,     "sessionId must not be null");
        Objects.requireNonNull(agentRole,     "agentRole must not be null");
        Objects.requireNonNull(goal,          "goal must not be null");
        Objects.requireNonNull(autonomyLevel, "autonomyLevel must not be null");
        if (maxIterations <= 0)
            throw new IllegalArgumentException(
                "maxIterations must be > 0, got: " + maxIterations);
        // channelType and channelPlatformId are intentionally nullable
    }

    /** Convenience: true when full channel stamping info is available. */
    public boolean hasChannelInfo() {
        return channelType != null && !channelType.isBlank()
            && channelPlatformId != null && !channelPlatformId.isBlank();
    }

    public static AgentExecutionContext from(
            RunAgentRequest req,
            String sessionId,
            String userId,
            String chatId,
            AutonomyLevel autonomyLevel
    ) {
        int max = req.maxIterations() != null ? req.maxIterations() : 5;
        return new AgentExecutionContext(
                sessionId,
                userId,
                chatId,
                req.agentRole(),
                req.goal(),
                autonomyLevel,
                max,
                null,  // no channel info for direct REST/API calls
                null
        );
    }
}