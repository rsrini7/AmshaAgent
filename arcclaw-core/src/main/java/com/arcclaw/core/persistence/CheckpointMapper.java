package com.arcclaw.core.persistence;

import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.state.AgentCheckpointEntity;
import com.arcclaw.core.state.CheckpointStatus;
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
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    public static AgentCheckpointEntity toEntity(CheckpointDto dto) {
        if (dto == null) return null;

        AgentCheckpointEntity entity = new AgentCheckpointEntity();
        entity.setId(dto.id());
        entity.setSessionId(dto.sessionId());
        entity.setAgentRole(dto.agentRole());
        entity.setStatus(dto.status() != null ? CheckpointStatus.valueOf(dto.status()) : null);
        entity.setAutonomyLevel(dto.autonomyLevel() != null ? AutonomyLevel.valueOf(dto.autonomyLevel()) : null);
        entity.setStateJson(dto.stateJson());
        entity.setChannelPlatformId(dto.channelPlatformId());
        entity.setChannelType(dto.channelType());
        entity.setOriginalGoal(dto.originalGoal());
        entity.setFailedAt(dto.failedAt());
        entity.setCompletedAt(dto.completedAt());
        entity.setFailReason(dto.failReason());
        entity.setPendingToolName(dto.pendingToolName());
        entity.setBatchToolDecisions(dto.batchToolDecisions());
        entity.setPendingToolParametersJson(dto.pendingToolParametersJson());
        entity.setPreExecutedResults(dto.preExecutedResults());
        entity.setMaxIterations(dto.maxIterations());
        entity.setIterationsUsed(dto.iterationsUsed());
        entity.setRetryAfterMs(dto.retryAfterMs());
        entity.setSystemNote(dto.systemNote());
        entity.setContextDataJson(dto.contextDataJson());
        entity.setErrorCode(dto.errorCode());
        entity.setTraceId(dto.traceId());
        entity.setRootCauseClass(dto.rootCauseClass());
        entity.setCreatedAt(dto.createdAt());
        entity.setUpdatedAt(dto.updatedAt());

        return entity;
    }
}
