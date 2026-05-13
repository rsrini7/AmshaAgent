package com.arcclaw.persistence.postgres;

import com.arcclaw.core.state.AgentCheckpointEntity;

import java.time.Instant;

import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;

final class CheckpointMapper {

    private CheckpointMapper() {}

    static CheckpointDto toDto(AgentCheckpointEntity c) {
        return new CheckpointDto(
                c.getId(), c.getSessionId(), c.getAgentRole(),
                c.getStatus() != null ? c.getStatus().name() : null,
                c.getAutonomyLevel() != null ? c.getAutonomyLevel().name() : null,
                null, c.getChannelPlatformId(), c.getChannelType(),
                c.getOriginalGoal(), c.getFailedAt(), c.getCompletedAt(), c.getFailReason(),
                c.getPendingToolName(), c.getBatchToolDecisions(), c.getPendingToolParametersJson(),
                c.getPreExecutedResults(), c.getMaxIterations(), c.getIterationsUsed(),
                c.getRetryAfterMs(), c.getSystemNote(), c.getContextDataJson(),
                c.getErrorCode(), c.getTraceId(), c.getRootCauseClass(),
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    static void updateEntity(AgentCheckpointEntity entity, CheckpointDto dto) {
        entity.setSessionId(dto.sessionId());
        entity.setAgentRole(dto.agentRole());
        entity.setStatus(dto.status() != null ? CheckpointStatus.valueOf(dto.status()) : null);
        entity.setPendingToolName(dto.pendingToolName());
        entity.setPendingToolParametersJson(dto.pendingToolParametersJson());
        entity.setBatchToolDecisions(dto.batchToolDecisions());
        entity.setPreExecutedResults(dto.preExecutedResults());
        entity.setAutonomyLevel(dto.autonomyLevel() != null ? AutonomyLevel.valueOf(dto.autonomyLevel()) : null);
        entity.setOriginalGoal(dto.originalGoal());
        entity.setContextDataJson(dto.contextDataJson());
        entity.setIterationsUsed(dto.iterationsUsed());
        entity.setMaxIterations(dto.maxIterations());
        entity.setRetryAfterMs(dto.retryAfterMs());
        entity.setChannelType(dto.channelType());
        entity.setChannelPlatformId(dto.channelPlatformId());
        entity.setSystemNote(dto.systemNote());
        entity.setUpdatedAt(Instant.now());
    }
}
