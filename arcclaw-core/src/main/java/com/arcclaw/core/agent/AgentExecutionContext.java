package com.arcclaw.core.agent;

import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.security.AutonomyLevel;

/**
 * Immutable context for an agent loop execution.
 * Encapsulates all inputs required to start or resume a loop.
 */
public record AgentExecutionContext(
        String sessionId,
        String userId,
        String chatId,
        String agentRole,
        String goal,
        AutonomyLevel autonomyLevel,
        int maxIterations
) {
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
                max
        );
    }
}
