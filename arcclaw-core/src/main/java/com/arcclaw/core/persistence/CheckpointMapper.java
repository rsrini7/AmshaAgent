package com.arcclaw.core.persistence;

import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.persistence.api.CheckpointDto;

public class CheckpointMapper {

    public static CheckpointDto toDto(AgentCheckpoint entity) {
        if (entity == null) return null;
        
        return new CheckpointDto(
            entity.getId(),
            entity.getSessionId(),
            entity.getAgentRole(),
            entity.getStatus() != null ? entity.getStatus().toString() : null,
            entity.getAutonomyLevel() != null ? entity.getAutonomyLevel().toString() : null,
            entity.getStateJson(),
            entity.getChannelPlatformId(),
            entity.getChannelType(),
            entity.getOriginalGoal(),
            entity.getFailedAt(),
            entity.getCompletedAt(),
            entity.getFailReason(),
            entity.getPendingToolName(),
            entity.getBatchToolDecisions(),
            entity.getPendingToolParametersJson(),
            entity.getPreExecutedResults(),
            entity.getMaxIterations(),
            entity.getIterationsUsed(),
            entity.getRetryAfterMs(),
            entity.getSystemNote(),
            entity.getContextDataJson(),
            entity.getErrorCode(),
            entity.getTraceId(),
            entity.getRootCauseClass(),
            entity.getParentCheckpointId(),
            entity.getDepth(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
