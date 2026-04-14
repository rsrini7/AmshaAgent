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
import jakarta.transaction.Transactional;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.persistence.CheckpointMapper;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.CheckpointUpsertResult;
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
        AutonomyLevel autonomy = WorkspaceContext.requireAutonomyOrSupervised();

        CheckpointUpsertResult result = storeAdapter.upsertWithSteps(
            sessionId, agentRole, json, autonomy.toString(), steps.size());
        
        if (result.created()) {
            String userId = WorkspaceContext.resolveUserId();
            String idempotencyKey = "CP_CREATED:" + sessionId + ":" + agentRole;
            auditService.recordWithIdempotency(sessionId, userId, agentRole,
                SecurityEventType.CHECKPOINT_CREATED, "autonomy=" + autonomy, idempotencyKey);
            log.infof("Created checkpoint for session=%s, role=%s, autonomy=%s", sessionId, agentRole, autonomy);
        }

        return CheckpointMapper.toEntity(result.checkpoint());
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        CheckpointDto savedDto = storeAdapter.save(CheckpointMapper.toDto(checkpoint));
        return CheckpointMapper.toEntity(savedDto);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<AgentCheckpoint> load(String sessionId, String agentRole) {
        return storeAdapter.findBySessionAndRole(sessionId, agentRole)
            .map(dto -> {
                AgentCheckpoint entity = CheckpointMapper.toEntity(dto);
                return entity; // map handles the null-to-empty transition
            });
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
            log.debugf(e, "Full stacktrace for deserialization failure of checkpoint id=%d",
                checkpoint.getId());
            return List.of();
        }
    }

    @Override
    public void markFailed(Long checkpointId, String reason) {
        markFailed(checkpointId, reason, null, null, null);
    }

    @Override
    public void markFailed(AgentCheckpoint checkpoint, String reason) {
        markFailed(checkpoint.getId(), reason);
    }

    @Override
    public void markFailed(Long checkpointId, String reason, String errorCode, String traceId, String rootCauseClass) {
        storeAdapter.markFailed(checkpointId, reason, errorCode, traceId, rootCauseClass);

        storeAdapter.findById(checkpointId).ifPresent(dto -> {
            String userId = WorkspaceContext.resolveUserId();
            String idempotencyKey = "CP_FAILED:" + checkpointId;
            String details = String.format("reason=%s errorCode=%s traceId=%s", reason, errorCode, traceId);
            auditService.recordWithIdempotency(dto.sessionId(), userId, dto.agentRole(),
                SecurityEventType.CHECKPOINT_FAILED, details, idempotencyKey);
            log.warnf("Checkpoint %s/%s marked FAILED: %s", dto.sessionId(), dto.agentRole(), reason);
        });
    }

    @Override
    public void markFailed(AgentCheckpoint checkpoint, String reason, String errorCode, String traceId, String rootCauseClass) {
        markFailed(checkpoint.getId(), reason, errorCode, traceId, rootCauseClass);
    }

    @Override
    public void markOrphaned(Long checkpointId, String reason) {
        storeAdapter.markOrphaned(checkpointId, reason);

        storeAdapter.findById(checkpointId).ifPresent(dto -> {
            String userId = WorkspaceContext.resolveUserId();
            String idempotencyKey = "CP_ORPHANED:" + checkpointId;
            auditService.recordWithIdempotency(dto.sessionId(), userId, dto.agentRole(),
                SecurityEventType.CHECKPOINT_ORPHANED, reason, idempotencyKey);
            log.warnf("Checkpoint %s/%s marked ORPHANED: %s", dto.sessionId(), dto.agentRole(), reason);
        });
    }

    @Override
    public void markCompleted(Long checkpointId) {
        storeAdapter.markCompleted(checkpointId);

        storeAdapter.findById(checkpointId).ifPresent(dto -> {
            String userId = WorkspaceContext.resolveUserId();
            String idempotencyKey = "CP_COMPLETED:" + checkpointId;
            auditService.recordWithIdempotency(dto.sessionId(), userId, dto.agentRole(),
                SecurityEventType.CHECKPOINT_COMPLETED, null, idempotencyKey);
            log.infof("Checkpoint %d marked COMPLETED for session %s", checkpointId, dto.sessionId());
        });
    }

    @Override
    public AgentCheckpoint saveAndFinalize(String sessionId, String agentRole,
                                           List<ReasoningStep> steps,
                                           CheckpointStatus finalStatus) {
        String json = serializeSteps(steps);
        Optional<AgentCheckpoint> existingOpt = load(sessionId, agentRole);
        boolean isNew = existingOpt.isEmpty();

        AgentCheckpoint checkpoint = existingOpt.orElseGet(AgentCheckpoint::new);
        
        checkpoint.setSessionId(sessionId);
        checkpoint.setAgentRole(agentRole);
        checkpoint.setStateJson(json);
        checkpoint.setStatus(finalStatus);
        
        if (finalStatus == CheckpointStatus.COMPLETED) {
            checkpoint.setCompletedAt(Instant.now());
        } else if (finalStatus == CheckpointStatus.FAILED) {
            checkpoint.setFailedAt(Instant.now());
        }

        CheckpointDto saved = storeAdapter.saveCheckpointWithSteps(
                CheckpointMapper.toDto(checkpoint), 
                steps.stream().map(this::mapToReasoningStepDto).toList());

        String userId = WorkspaceContext.resolveUserId();

        // Emit CP_CREATED for brand-new checkpoints (single-shot agents)
        if (isNew) {
            String createdKey = "CP_CREATED:" + sessionId + ":" + agentRole;
            auditService.recordWithIdempotency(sessionId, userId, agentRole,
                SecurityEventType.CHECKPOINT_CREATED, null, createdKey);
            log.infof("Created new checkpoint during finalization session=%s role=%s",
                sessionId, agentRole);
        }

        SecurityEventType eventType = finalStatus == CheckpointStatus.COMPLETED
                ? SecurityEventType.CHECKPOINT_COMPLETED
                : SecurityEventType.CHECKPOINT_FAILED;
        String idempotencyKey = (finalStatus == CheckpointStatus.COMPLETED ? "CP_COMPLETED:" : "CP_FAILED:") + saved.id();
        auditService.recordWithIdempotency(sessionId, userId, agentRole, eventType, null, idempotencyKey);

        return CheckpointMapper.toEntity(saved);
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
                .map(CheckpointMapper::toEntity);
    }

    @Override
    public Optional<AutonomyLevel> loadLatestAutonomyForSession(String sessionId) {
        return storeAdapter.findLatestBySession(sessionId)
            .flatMap(d -> Optional.ofNullable(d.autonomyLevel())
                .map(AutonomyLevel::valueOf));
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public boolean updateBatchToolDecision(String sessionId, String agentRole,
                                           String toolName, String decision) {
        return storeAdapter.atomicUpdateBatchToolDecision(sessionId, agentRole, toolName, decision);
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
        return storeAdapter.findStaleApprovals(cutoff).stream().map(CheckpointMapper::toEntity).toList();
    }

    @Override
    public List<AgentCheckpoint> findByStatus(CheckpointStatus status) {
        return storeAdapter.findByStatus(status.toString()).stream().map(CheckpointMapper::toEntity).toList();
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
        return storeAdapter.findLatestAwaitingApproval(sessionId)
                .map(CheckpointMapper::toEntity);
    }

    @Override
    public Optional<AgentCheckpoint> findLatestCheckpoint(String sessionId) {
        return storeAdapter.findLatestBySession(sessionId).map(CheckpointMapper::toEntity);
    }

    @Override
    public Optional<String> findLatestRole(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return storeAdapter.findLatestNonArchivedBySession(sessionId)
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
