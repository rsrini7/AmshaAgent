package com.arcclaw.core.state;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.persistence.CheckpointMapper;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * Implementation of {@link StatefulAgentStore} that delegates to a {@link CheckpointStorePort}.
 * This class is backend-agnostic and relies on the persistence adapter for database operations.
 */
@ApplicationScoped
public class StatefulAgentStoreImpl implements StatefulAgentStore {

    private static final Logger log = Logger.getLogger(StatefulAgentStoreImpl.class);
    private static final TypeReference<List<ReasoningStep>> STEPS_TYPE = new TypeReference<>() {};

    private final ConcurrentHashMap<String, Instant> resumingBatches = new ConcurrentHashMap<>();

    @Inject ObjectMapper objectMapper;
    @Inject CheckpointStorePort storeAdapter;
    @Inject AuditService auditService;

    @Override
    public AgentCheckpoint save(String sessionId, String agentRole, List<ReasoningStep> steps) {
        String json = serializeSteps(steps);
        Optional<AgentCheckpoint> existing = load(sessionId, agentRole);

        AgentCheckpoint checkpoint;
        AutonomyLevel autonomy = WorkspaceContext.requireAutonomyOrSupervised();

        if (existing.isPresent()) {
            checkpoint = existing.get();
            checkpoint.setStateJson(json);
            checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
            checkpoint.setAutonomyLevel(autonomy);
            checkpoint.setIterationsUsed(steps.size());
        } else {
            checkpoint = new AgentCheckpoint();
            checkpoint.setSessionId(sessionId);
            checkpoint.setAgentRole(agentRole);
            checkpoint.setStateJson(json);
            checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
            checkpoint.setAutonomyLevel(autonomy);
            checkpoint.setIterationsUsed(steps.size());

            String userId = WorkspaceContext.resolveUserId();
            String idempotencyKey = "CP_CREATED:" + sessionId + ":" + agentRole;
            auditService.recordWithIdempotency(sessionId, userId, agentRole,
                SecurityEventType.CHECKPOINT_CREATED, "autonomy=" + autonomy, idempotencyKey);
        }

        CheckpointDto savedDto = storeAdapter.save(entityToDto(checkpoint));
        return dtoToEntity(savedDto);
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        CheckpointDto savedDto = storeAdapter.save(entityToDto(checkpoint));
        return dtoToEntity(savedDto);
    }

    @Override
    public Optional<AgentCheckpoint> load(String sessionId, String agentRole) {
        return storeAdapter.findBySessionAndRole(sessionId, agentRole).map(this::dtoToEntity);
    }

    @Override
    public List<ReasoningStep> deserializeSteps(AgentCheckpoint checkpoint) {
        if (checkpoint.getStateJson() == null || checkpoint.getStateJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(checkpoint.getStateJson(), STEPS_TYPE);
        } catch (JsonProcessingException e) {
            log.errorf("Failed to deserialize stateJson for checkpoint id=%d: %s",
                       checkpoint.getId(), e.getMessage());
            return List.of();
        }
    }

    @Override
    public void markFailed(Long checkpointId, String reason) {
        storeAdapter.findById(checkpointId).ifPresent(dto -> {
            CheckpointDto updated = new CheckpointDto(
                dto.id(), dto.sessionId(), dto.agentRole(), 
                CheckpointStatus.FAILED.toString(),
                dto.autonomyLevel(), dto.stateJson(), dto.channelPlatformId(), dto.channelType(), 
                dto.originalGoal(), Instant.now(), dto.completedAt(), reason,
                dto.pendingToolName(), dto.batchToolDecisions(), dto.pendingToolParametersJson(), 
                dto.preExecutedResults(), dto.maxIterations(), dto.iterationsUsed(), 
                dto.retryAfterMs(), dto.systemNote(), dto.errorCode(), dto.traceId(), dto.rootCauseClass(),
                dto.createdAt(), dto.updatedAt()
            );
            storeAdapter.save(updated);
        });
    }

    @Override
    public void markFailed(AgentCheckpoint checkpoint, String reason) {
        markFailed(checkpoint.getId(), reason);
    }

    @Override
    public void markFailed(Long checkpointId, String reason, String errorCode, String traceId, String rootCauseClass) {
        storeAdapter.findById(checkpointId).ifPresent(dto -> {
            CheckpointDto updated = new CheckpointDto(
                dto.id(), dto.sessionId(), dto.agentRole(), 
                CheckpointStatus.FAILED.toString(),
                dto.autonomyLevel(), dto.stateJson(), dto.channelPlatformId(), dto.channelType(), 
                dto.originalGoal(), Instant.now(), dto.completedAt(), reason,
                dto.pendingToolName(), dto.batchToolDecisions(), dto.pendingToolParametersJson(), 
                dto.preExecutedResults(), dto.maxIterations(), dto.iterationsUsed(), 
                dto.retryAfterMs(), dto.systemNote(), errorCode, traceId, rootCauseClass,
                dto.createdAt(), dto.updatedAt()
            );
            storeAdapter.save(updated);
        });
    }

    @Override
    public void markFailed(AgentCheckpoint checkpoint, String reason, String errorCode, String traceId, String rootCauseClass) {
        markFailed(checkpoint.getId(), reason, errorCode, traceId, rootCauseClass);
    }

    @Override
    public void markOrphaned(Long checkpointId, String reason) {
        storeAdapter.findById(checkpointId).ifPresent(dto -> {
            CheckpointDto updated = new CheckpointDto(
                dto.id(), dto.sessionId(), dto.agentRole(), 
                "ORPHANED",
                dto.autonomyLevel(), dto.stateJson(), dto.channelPlatformId(), dto.channelType(), 
                dto.originalGoal(), Instant.now(), dto.completedAt(), reason,
                dto.pendingToolName(), dto.batchToolDecisions(), dto.pendingToolParametersJson(), 
                dto.preExecutedResults(), dto.maxIterations(), dto.iterationsUsed(), 
                dto.retryAfterMs(), dto.systemNote(), dto.errorCode(), dto.traceId(), dto.rootCauseClass(),
                dto.createdAt(), dto.updatedAt()
            );
            storeAdapter.save(updated);

            String userId = WorkspaceContext.resolveUserId();
            String idempotencyKey = "CP_ORPHANED:" + checkpointId;
            auditService.recordWithIdempotency(dto.sessionId(), userId, dto.agentRole(),
                SecurityEventType.CHECKPOINT_ORPHANED, null, idempotencyKey);
        });
    }

    @Override
    public void markCompleted(Long checkpointId) {
        storeAdapter.findById(checkpointId).ifPresent(dto -> {
            CheckpointDto updated = new CheckpointDto(
                dto.id(), dto.sessionId(), dto.agentRole(), 
                CheckpointStatus.COMPLETED.toString(),
                dto.autonomyLevel(), dto.stateJson(), dto.channelPlatformId(), dto.channelType(), 
                dto.originalGoal(), dto.failedAt(), Instant.now(), null,
                dto.pendingToolName(), dto.batchToolDecisions(), dto.pendingToolParametersJson(), 
                dto.preExecutedResults(), dto.maxIterations(), dto.iterationsUsed(), 
                dto.retryAfterMs(), dto.systemNote(), dto.errorCode(), dto.traceId(), dto.rootCauseClass(),
                dto.createdAt(), dto.updatedAt()
            );
            storeAdapter.save(updated);

            String userId = WorkspaceContext.resolveUserId();
            String idempotencyKey = "CP_COMPLETED:" + checkpointId;
            auditService.recordWithIdempotency(dto.sessionId(), userId, dto.agentRole(),
                SecurityEventType.CHECKPOINT_COMPLETED, null, idempotencyKey);
        });
    }

    @Override
    public AgentCheckpoint saveAndFinalize(String sessionId, String agentRole,
                                           List<ReasoningStep> steps,
                                           CheckpointStatus finalStatus) {
        AgentCheckpoint checkpoint = save(sessionId, agentRole, steps);
        checkpoint.setStatus(finalStatus);
        if (finalStatus == CheckpointStatus.COMPLETED) {
            checkpoint.setCompletedAt(Instant.now());
        } else if (finalStatus == CheckpointStatus.FAILED) {
            checkpoint.setFailedAt(Instant.now());
        }

        CheckpointDto saved = storeAdapter.saveCheckpointWithSteps(entityToDto(checkpoint), steps.stream().map(this::mapToReasoningStepDto).toList());

        String userId = WorkspaceContext.resolveUserId();
        SecurityEventType eventType = finalStatus == CheckpointStatus.COMPLETED
                ? SecurityEventType.CHECKPOINT_COMPLETED
                : SecurityEventType.CHECKPOINT_FAILED;
        String idempotencyKey = (finalStatus == CheckpointStatus.COMPLETED ? "CP_COMPLETED:" : "CP_FAILED:") + saved.id();
        auditService.recordWithIdempotency(sessionId, userId, agentRole, eventType, null, idempotencyKey);

        return dtoToEntity(saved);
    }

    @Override
    public List<ReasoningStep> loadSteps(Long checkpointId) {
        return storeAdapter.loadSteps(checkpointId).stream()
                .map(this::mapToReasoningStep)
                .toList();
    }

    @Override
    public Optional<AgentCheckpoint> findLatestAwaitingApprovalForCron(String cronId) {
        String sessionPrefix = AgentSentinels.CRON_SESSION_PREFIX + cronId + "-";
        return storeAdapter.findLatestAwaitingApprovalWithPrefix(sessionPrefix)
                .map(this::dtoToEntity);
    }

    @Override
    public Optional<AutonomyLevel> loadLatestAutonomyForSession(String sessionId) {
        return storeAdapter.findLatestBySession(sessionId).map(d -> AutonomyLevel.valueOf(d.autonomyLevel()));
    }

    @Override
    public boolean updateBatchToolDecision(String sessionId, String agentRole,
                                           String toolName, String decision) {
        Optional<CheckpointDto> dtoOpt = storeAdapter.findBySessionAndRole(sessionId, agentRole);
        if (dtoOpt.isEmpty()) return false;

        CheckpointDto dto = dtoOpt.get();
        if (!CheckpointStatus.AWAITING_APPROVAL.toString().equals(dto.status()) || dto.batchToolDecisions() == null) {
            return false;
        }

        try {
            Map<String, ToolDecision> map = objectMapper.readValue(
                dto.batchToolDecisions(),
                new TypeReference<Map<String, ToolDecision>>() {});
            if (!map.containsKey(toolName)) return false;
            map.put(toolName, ToolDecision.valueOf(decision));
            
            CheckpointDto updated = new CheckpointDto(
                dto.id(), dto.sessionId(), dto.agentRole(), dto.status(),
                dto.autonomyLevel(), dto.stateJson(), dto.channelPlatformId(), dto.channelType(),
                dto.originalGoal(), dto.failedAt(), dto.completedAt(), dto.failReason(),
                dto.pendingToolName(), objectMapper.writeValueAsString(map),
                dto.pendingToolParametersJson(), dto.preExecutedResults(), dto.maxIterations(),
                dto.iterationsUsed(), dto.retryAfterMs(), dto.systemNote(), dto.errorCode(),
                dto.traceId(), dto.rootCauseClass(), dto.createdAt(), dto.updatedAt()
            );
            storeAdapter.save(updated);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isAllBatchToolsDecided(String sessionId, String agentRole) {
        Optional<CheckpointDto> dtoOpt = storeAdapter.findBySessionAndRole(sessionId, agentRole);
        if (dtoOpt.isEmpty()) return false;

        CheckpointDto dto = dtoOpt.get();
        if (dto.batchToolDecisions() == null) return false;

        try {
            Map<String, ToolDecision> map = objectMapper.readValue(
                dto.batchToolDecisions(),
                new TypeReference<Map<String, ToolDecision>>() {});
            return map.values().stream().noneMatch(val -> ToolDecision.PENDING == val);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void deleteCheckpoint(String sessionId, String role) {
        storeAdapter.deleteCheckpoint(sessionId, role);
    }

    @Override
    public List<AgentCheckpoint> findStaleAwaitingApproval(Instant cutoff) {
        return storeAdapter.findStaleApprovals(cutoff).stream().map(this::dtoToEntity).toList();
    }

    @Override
    public List<AgentCheckpoint> findByStatus(CheckpointStatus status) {
        return storeAdapter.findByStatus(status.toString()).stream().map(this::dtoToEntity).toList();
    }

    @Override
    public boolean markBatchResuming(String sessionId) {
        return resumingBatches.putIfAbsent(sessionId, Instant.now()) == null;
    }

    @Override
    public boolean isBatchResuming(String sessionId) {
        Instant start = resumingBatches.get(sessionId);
        if (start == null) return false;
        if (Duration.between(start, Instant.now()).toMinutes() > 30) {
            resumingBatches.remove(sessionId);
            return false;
        }
        return true;
    }

    @Override
    public void clearBatchResuming(String sessionId) {
        resumingBatches.remove(sessionId);
    }

    @Override
    public Optional<AgentCheckpoint> findLatestAwaitingApproval(String sessionId) {
        return storeAdapter.findActiveBySession(sessionId).stream()
                .filter(d -> CheckpointStatus.AWAITING_APPROVAL.toString().equals(d.status()))
                .map(this::dtoToEntity)
                .findFirst();
    }

    @Override
    public Optional<AgentCheckpoint> findLatestCheckpoint(String sessionId) {
        return storeAdapter.findLatestBySession(sessionId).map(this::dtoToEntity);
    }

    @Override
    public Optional<String> findLatestRole(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return storeAdapter.findLatestBySessionAndStatus(sessionId, CheckpointStatus.ARCHIVED.name())
                .map(CheckpointDto::agentRole);
    }

    @Override
    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        return storeAdapter.isCheckpointExpired(sessionId, timeoutHours);
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        return storeAdapter.isSessionActive(sessionId);
    }

    @Override
    public boolean hasPendingApproval(String sessionId) {
        return storeAdapter.hasPendingApproval(sessionId);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private CheckpointDto entityToDto(AgentCheckpoint entity) {
        return CheckpointMapper.toDto(entity);
    }

    private AgentCheckpoint dtoToEntity(CheckpointDto dto) {
        AgentCheckpoint entity = new AgentCheckpoint();
        entity.setId(dto.id());
        entity.setSessionId(dto.sessionId());
        entity.setAgentRole(dto.agentRole());
        if (dto.status() != null) entity.setStatus(CheckpointStatus.valueOf(dto.status()));
        if (dto.autonomyLevel() != null) entity.setAutonomyLevel(AutonomyLevel.valueOf(dto.autonomyLevel()));
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
        entity.setErrorCode(dto.errorCode());
        entity.setTraceId(dto.traceId());
        entity.setRootCauseClass(dto.rootCauseClass());
        entity.setCreatedAt(dto.createdAt());
        entity.setUpdatedAt(dto.updatedAt());
        return entity;
    }

    private ReasoningStepDto mapToReasoningStepDto(ReasoningStep step) {
        return new ReasoningStepDto(
            serialize(step.thought()), step.action(), serialize(step.actionInput()),
            step.observation(), step.inputTokens(), step.outputTokens(),
            step.success(), step.errorCode(), step.traceId(), step.rootCauseClass()
        );
    }

    private ReasoningStep mapToReasoningStep(ReasoningStepDto dto) {
        return ReasoningStep.of(
            deserialize(dto.thoughtJson(), String.class),
            dto.action(),
            deserialize(dto.actionInputJson(), new TypeReference<Map<String, Object>>() {}),
            dto.observation(), dto.inputTokens(), dto.outputTokens(), dto.success(),
            dto.errorCode(), dto.traceId(), dto.rootCauseClass()
        );
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, clazz); }
        catch (JsonProcessingException e) { return null; }
    }

    private <T> T deserialize(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, typeRef); }
        catch (JsonProcessingException e) { return null; }
    }

    private String serialize(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private String serializeSteps(List<ReasoningStep> steps) {
        try { return (steps == null || steps.isEmpty()) ? "[]" : objectMapper.writeValueAsString(steps); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
