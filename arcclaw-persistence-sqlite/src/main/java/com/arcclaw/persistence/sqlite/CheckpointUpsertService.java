package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.persistence.CheckpointMapper;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointUpsertResult;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.arcclaw.persistence.common.entity.AgentCheckpointEntity;

/**
 * Handles complex upsert logic and entity-dto mapping for checkpoints.
 */
@ApplicationScoped
public class CheckpointUpsertService {

    private final CheckpointRepository checkpointRepo;
    private final ReasoningStepRepository stepRepo;
    private final CheckpointBatchOperations batchOps;

    @Inject
    public CheckpointUpsertService(CheckpointRepository checkpointRepo,
                                  ReasoningStepRepository stepRepo,
                                  CheckpointBatchOperations batchOps) {
        this.checkpointRepo = checkpointRepo;
        this.stepRepo = stepRepo;
        this.batchOps = batchOps;
    }

    protected CheckpointUpsertService() {
        this(null, null, null);
    }

    public CheckpointUpsertResult doUpsertWithSteps(String sessionId, String agentRole, 
            String stateJson, String autonomy, int iterationsUsed, String originalGoal, String contextDataJson) {
        
        List<AgentCheckpointEntity> existing = checkpointRepo.findAnyBySessionAndRole(sessionId, agentRole);

        AgentCheckpointEntity entity;
        boolean created = false;
        if (existing.isEmpty()) {
            entity = new AgentCheckpointEntity();
            entity.setSessionId(sessionId);
            entity.setAgentRole(agentRole);
            entity.setCreatedAt(Instant.now());
            created = true;
        } else {
            entity = existing.get(0);
        }

        if (entity.getStatus() != CheckpointStatus.AWAITING_APPROVAL && entity.getStatus() != CheckpointStatus.RESUMING) {
            entity.setStatus(CheckpointStatus.IN_PROGRESS);
        }
        
        entity.setStateJson(stateJson);
        if (autonomy != null) {
            entity.setAutonomyLevel(AutonomyLevel.valueOf(autonomy));
        }
        entity.setIterationsUsed(iterationsUsed);
        entity.setUpdatedAt(Instant.now());
        if (contextDataJson != null) {
            entity.setContextDataJson(contextDataJson);
        }

        if (originalGoal != null && (entity.getOriginalGoal() == null || entity.getOriginalGoal().isBlank())) {
            entity.setOriginalGoal(originalGoal);
        }

        if (created) {
            entity = checkpointRepo.persist(entity);
        } else {
            entity = checkpointRepo.merge(entity);
        }
        
        return new CheckpointUpsertResult(CheckpointMapper.toDto(entity), created);
    }

    public CheckpointUpsertResult doUpsertAndFinalize(String sessionId, String agentRole, 
        String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal, String contextDataJson) {
        
        List<AgentCheckpointEntity> existing = checkpointRepo.findAnyBySessionAndRole(sessionId, agentRole);

        AgentCheckpointEntity entity;
        boolean created = false;
        CheckpointStatus status = CheckpointStatus.valueOf(finalStatus);

        if (existing.isEmpty()) {
            entity = new AgentCheckpointEntity();
            entity.setSessionId(sessionId);
            entity.setAgentRole(agentRole);
            entity.setCreatedAt(Instant.now());
            created = true;
        } else {
            entity = existing.get(0);
        }

        entity.setStatus(status);
        entity.setStateJson(stateJson);
        entity.setUpdatedAt(Instant.now());
        if (contextDataJson != null) {
            entity.setContextDataJson(contextDataJson);
        }

        if (status == CheckpointStatus.COMPLETED) {
            entity.setCompletedAt(Instant.now());
        } else if (status == CheckpointStatus.FAILED) {
            entity.setFailedAt(Instant.now());
        }

        if (originalGoal != null && (entity.getOriginalGoal() == null || entity.getOriginalGoal().isBlank())) {
            entity.setOriginalGoal(originalGoal);
        }

        if (created) {
            entity = checkpointRepo.persist(entity);
        } else {
            entity = checkpointRepo.merge(entity);
        }
        
        CheckpointDto savedDto = CheckpointMapper.toDto(entity);
        stepRepo.persistSteps(savedDto.id(), steps);
        
        return new CheckpointUpsertResult(savedDto, created);
    }

    public void updateEntityFromDto(AgentCheckpointEntity entity, CheckpointDto dto) {
        entity.setSessionId(dto.sessionId());
        entity.setAgentRole(dto.agentRole());
        
        CheckpointStatus oldStatus = entity.getStatus();
        if (dto.status() != null) {
            entity.setStatus(CheckpointStatus.valueOf(dto.status()));
        }
        if (dto.autonomyLevel() != null) {
            entity.setAutonomyLevel(AutonomyLevel.valueOf(dto.autonomyLevel()));
        }
        entity.setStateJson(dto.stateJson());
        entity.setChannelPlatformId(dto.channelPlatformId());
        entity.setChannelType(dto.channelType());
        entity.setOriginalGoal(dto.originalGoal());
        entity.setFailedAt(dto.failedAt());
        entity.setCompletedAt(dto.completedAt());
        entity.setFailReason(dto.failReason());
        entity.setPendingToolName(dto.pendingToolName());
        
        if (CheckpointStatus.AWAITING_APPROVAL.toString().equals(dto.status()) 
                && oldStatus == CheckpointStatus.AWAITING_APPROVAL) {
            batchOps.mergeBatchDecisions(entity, dto);
        } else {
            entity.setBatchToolDecisions(dto.batchToolDecisions());
        }
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
        entity.setUpdatedAt(dto.updatedAt());
    }
}

