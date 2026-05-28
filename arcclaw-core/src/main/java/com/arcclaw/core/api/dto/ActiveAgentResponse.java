package com.arcclaw.core.api.dto;

import java.time.Instant;
import java.util.List;

public record ActiveAgentResponse(int count, List<AgentSummary> agents) {
    public record AgentSummary(
            Long checkpointId,
            String sessionId,
            String agentRole,
            String status,
            String autonomyLevel,
            int depth,
            Long parentCheckpointId,
            String originalGoal,
            String channelType,
            String channelPlatformId,
            int iterationsUsed,
            Integer maxIterations,
            String pendingToolName,
            Instant createdAt,
            Instant updatedAt) {}
}
