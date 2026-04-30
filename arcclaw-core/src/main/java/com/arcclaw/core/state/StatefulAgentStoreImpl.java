package com.arcclaw.core.state;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.persistence.CheckpointMapper;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.CheckpointUpsertResult;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Domain-level orchestrator for agent checkpoint persistence.
 *
 * <p>Post-C2 Phase 1 decomposition: This class delegates three extracted
 * responsibilities to focused services:
 * <ul>
 *   <li><strong>Serialization</strong> → {@link CheckpointSerializer}</li>
 *   <li><strong>Status transitions + audit</strong> → {@link CheckpointTransitionService}</li>
 *   <li><strong>Double-resume guard</strong> → {@link ResumeCoordinationGuard}</li>
 * </ul>
 *
 * <p>The remaining responsibilities are:
 * <ol>
 *   <li>Domain save/load orchestration (delegating storage to {@link CheckpointStorePort})</li>
 *   <li>Query delegation (findByStatus, findLatestX, isSessionActive, etc.)</li>
 *   <li>Batch tool decision management</li>
 * </ol>
 *
 * <p><strong>§3.4 / §7.29 (MUST):</strong> The public API for the double-resume guard
 * ({@link #markBatchResuming}, {@link #isBatchResuming}, {@link #clearBatchResuming})
 * remains on THIS class and THIS interface. External callers (WorkspaceManager,
 * CheckpointResumeHandler) MUST call through {@link StatefulAgentStore}, never
 * inject {@link ResumeCoordinationGuard} directly.
 */
@ApplicationScoped
public class StatefulAgentStoreImpl implements StatefulAgentStore {

    @Inject CheckpointStorePort       storeAdapter;
    @Inject CheckpointSerializer      serializer;
    @Inject CheckpointTransitionService transitionService;
    @Inject ResumeCoordinationGuard   resumeGuard;

    // ── Save / Load ───────────────────────────────────────────────────────────

    @Override
    public AgentCheckpoint save(String sessionId, String agentRole, List<ReasoningStep> steps, String goal) {
        String json = serializer.serializeSteps(steps);
        Map<String, Object> contextData = WorkspaceContext.getData();
        String contextDataJson = (contextData == null || contextData.isEmpty()) 
            ? null : serializer.serialize(contextData);
        AutonomyLevel autonomy = WorkspaceContext.requireAutonomyOrSupervised();
        CheckpointUpsertResult result = storeAdapter.upsertWithSteps(
                sessionId, agentRole, json, autonomy.toString(), steps.size(), goal, contextDataJson);
        if (result.created()) {
            transitionService.emitCreated(
                    sessionId, agentRole, WorkspaceContext.resolveUserId(), autonomy,
                    result.checkpoint().id(), result.checkpoint().createdAt());
        }
        return CheckpointMapper.toEntity(result.checkpoint());
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        CheckpointDto saved = storeAdapter.save(CheckpointMapper.toDto(checkpoint));
        return CheckpointMapper.toEntity(saved);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<AgentCheckpoint> load(String sessionId, String agentRole) {
        return storeAdapter.findBySessionAndRole(sessionId, agentRole)
                .map(CheckpointMapper::toEntity);
    }

    @Override
    public List<ReasoningStep> deserializeSteps(AgentCheckpoint checkpoint) {
        if (checkpoint == null) return List.of();
        
        // 1. Try granular rows (new way)
        if (checkpoint.getId() != null) {
            List<ReasoningStep> granular = loadSteps(checkpoint.getId());
            if (!granular.isEmpty()) return granular;
        }

        // 2. Fallback to stateJson (old way)
        return serializer.deserializeSteps(checkpoint);
    }

    @Override
    public Map<String, Object> deserializeContextData(String json) {
        return serializer.deserialize(json, new TypeReference<Map<String, Object>>() {});
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void saveSteps(Long checkpointId, List<ReasoningStep> steps) {
        if (checkpointId == null || steps == null) return;
        List<ReasoningStepDto> dtos = steps.stream()
                .map(serializer::toDto)
                .toList();
        storeAdapter.saveSteps(checkpointId, dtos);
    }

    @Override
    public AgentCheckpoint saveWithSteps(AgentCheckpoint checkpoint, List<ReasoningStep> steps) {
        List<ReasoningStepDto> dtos = steps == null ? List.of() : steps.stream()
                .map(serializer::toDto)
                .toList();
        CheckpointDto saved = storeAdapter.saveCheckpointWithSteps(CheckpointMapper.toDto(checkpoint), dtos);
        return CheckpointMapper.toEntity(saved);
    }

    @Override
    public AgentCheckpoint saveAndFinalize(String sessionId, String agentRole,
                                           List<ReasoningStep> steps,
                                           CheckpointStatus finalStatus,
                                           String goal) {
        String json = serializer.serializeSteps(steps);
        Map<String, Object> contextData = WorkspaceContext.getData();
        String contextDataJson = (contextData == null || contextData.isEmpty()) 
            ? null : serializer.serialize(contextData);
        List<ReasoningStepDto> stepDtos = 
            steps.stream().map(serializer::toDto).toList();

        CheckpointUpsertResult result = storeAdapter.upsertAndFinalize(
                sessionId, agentRole, json, finalStatus.name(), stepDtos, goal, contextDataJson);

        CheckpointDto saved = result.checkpoint();
        boolean isNew = result.created();

        String userId = WorkspaceContext.resolveUserId();
        if (isNew) {
            // Single-shot agents create the checkpoint here - emit CREATED once.
            transitionService.emitCreated(sessionId, agentRole, userId,
                    WorkspaceContext.requireAutonomyOrSupervised(), saved.id(), saved.createdAt());
        }
        // Emit audit-only: status is already baked into the checkpoint by
        // upsertAndFinalize above.
        transitionService.emitFinalStatus(sessionId, agentRole, userId, finalStatus, saved.id(), saved.createdAt());
        if (finalStatus == CheckpointStatus.COMPLETED) {
            storeAdapter.archive(saved.id());
        }
        return CheckpointMapper.toEntity(saved);
    }

    @Override
    public List<ReasoningStep> loadSteps(Long checkpointId) {
        return storeAdapter.loadSteps(checkpointId).stream()
                .map(serializer::fromDto)
                .toList();
    }

    // ── Status transitions (all delegated to CheckpointTransitionService) ─────

    @Override public void markFailed(Long id, String reason)
        { transitionService.markFailed(id, reason); }

    @Override public void markFailed(AgentCheckpoint cp, String reason)
        { transitionService.markFailed(cp, reason); }

    @Override public void markFailed(Long id, String reason,
                                     String errorCode, String traceId, String rootCauseClass)
        { transitionService.markFailed(id, reason, errorCode, traceId, rootCauseClass); }

    @Override public void markFailed(AgentCheckpoint cp, String reason,
                                     String errorCode, String traceId, String rootCauseClass)
        { transitionService.markFailed(cp, reason, errorCode, traceId, rootCauseClass); }

    @Override public void markOrphaned(Long checkpointId, String reason)
        { transitionService.markOrphaned(checkpointId, reason); }

    @Override public void markCompleted(Long checkpointId)
        { transitionService.markCompleted(checkpointId); }

    @Override public void archive(Long checkpointId)
        { storeAdapter.archive(checkpointId); }

    @Override
    public boolean claimForResume(String sessionId, String agentRole) {
        return storeAdapter.claimForResume(sessionId, agentRole);
    }

    // ── Resume guard (§3.4 / §7.29 - all delegated, public API stays here) ───

    /**
     * Delegates to {@link CheckpointStorePort#claimForResume} for distributed safety.
     * Also maintains an in-memory mark in {@link ResumeCoordinationGuard} for
     * instance-local backpressure.
     */
    @Override
    public boolean markBatchResuming(String sessionId, String agentRole) {
        return markBatchResuming(sessionId, agentRole, false);
    }

    @Override
    public boolean markBatchResuming(String sessionId, String agentRole, boolean hasBrowserTool) {
        // 1. Instance-local check (fast path)
        if (resumeGuard.isResuming(sessionId)) {
            return false;
        }

        // 2. Distributed DB-backed lock (§T5-4)
        // Transition AWAITING_APPROVAL -> RESUMING in SQLite
        boolean claimed = storeAdapter.claimForResume(sessionId, agentRole);
        if (claimed) {
            resumeGuard.markResuming(sessionId, hasBrowserTool);
        }
        return claimed;
    }

    @Override
    public boolean markResuming(String sessionId) {
        return resumeGuard.markResuming(sessionId, false);
    }

    @Override
    public boolean claimForRetry(Long id) {
        return storeAdapter.claimForRetry(id);
    }

    /**
     * §7.29: Returns true if the session is currently RESUMING (JVM-local).
     */
    @Override public boolean isBatchResuming(String sessionId)
        { return resumeGuard.isResuming(sessionId); }

    /**
     * §3.4 / §7.29 (MUST): MUST be called in the finally block of the resume
     * path and by WorkspaceManager.terminateSession(). Never suppress.
     */
    @Override public void clearBatchResuming(String sessionId)
        { resumeGuard.clearResuming(sessionId); }

    @Override public void clearResuming(String sessionId)
        { resumeGuard.clearResuming(sessionId); }

    @Override
    public void releaseResumeClaim(String sessionId, String agentRole) {
        storeAdapter.releaseResumeClaim(sessionId, agentRole);
        resumeGuard.clearResuming(sessionId);
    }

    // ── Batch tool decisions ──────────────────────────────────────────────────

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public boolean updateBatchToolDecision(String sessionId, String agentRole,
                                           String toolName, String decision) {
        return storeAdapter.atomicUpdateBatchToolDecision(sessionId, agentRole, toolName, decision);
    }

    @Override
    public boolean isAllBatchToolsDecided(String sessionId, String agentRole) {
        Optional<CheckpointDto> dtoOpt = storeAdapter.findBySessionAndRole(sessionId, agentRole);
        if (dtoOpt.isEmpty() || dtoOpt.get().batchToolDecisions() == null) return false;
        Map<String, ToolDecision> map = serializer.deserialize(
                dtoOpt.get().batchToolDecisions(),
                new TypeReference<Map<String, ToolDecision>>() {});
        return map != null && map.values().stream().noneMatch(v -> ToolDecision.PENDING == v);
    }

    // ── Query delegation ──────────────────────────────────────────────────────

    @Override public Optional<AgentCheckpoint> findLatestAwaitingApproval(String sessionId)
        { return storeAdapter.findLatestAwaitingApproval(sessionId).map(CheckpointMapper::toEntity); }

    @Override
    public Optional<AgentCheckpoint> findLatestAwaitingApprovalBySessionContaining(String segment) {
        return storeAdapter.findLatestAwaitingApprovalBySessionContaining(segment)
                .map(CheckpointMapper::toEntity);
    }

    @Override public Optional<AgentCheckpoint> findLatestCheckpoint(String sessionId)
        { return storeAdapter.findLatestBySession(sessionId).map(CheckpointMapper::toEntity); }

    @Override public Optional<AgentCheckpoint> findLatestNonArchivedCheckpoint(String sessionId)
        { return storeAdapter.findLatestNonArchivedBySession(sessionId).map(CheckpointMapper::toEntity); }

    @Override
    public Optional<AgentCheckpoint> findLatestByStatus(String sessionId, CheckpointStatus status) {
        return storeAdapter.findLatestBySessionAndStatus(sessionId, status.name())
                .map(CheckpointMapper::toEntity);
    }

    @Override public Optional<String> findLatestRole(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return storeAdapter.findLatestNonArchivedBySession(sessionId).map(dto -> dto.agentRole());
    }

    @Override public Optional<AutonomyLevel> loadLatestAutonomyForSession(String sessionId) {
        return storeAdapter.findLatestBySession(sessionId)
                .flatMap(d -> Optional.ofNullable(d.autonomyLevel()))
                .map(AutonomyLevel::valueOf);
    }

    @Override public List<AgentCheckpoint> findStaleAwaitingApproval(Instant cutoff)
        { return storeAdapter.findStaleApprovals(cutoff).stream().map(CheckpointMapper::toEntity).toList(); }

    @Override public List<AgentCheckpoint> findByStatus(CheckpointStatus status)
        { return storeAdapter.findByStatus(status.toString()).stream().map(CheckpointMapper::toEntity).toList(); }

    @Override public boolean isCheckpointExpired(String sessionId, long timeoutHours)
        { return storeAdapter.isCheckpointExpired(sessionId, timeoutHours); }

    @Override public boolean isSessionActive(String sessionId)
        { return storeAdapter.isSessionActive(sessionId); }

    @Override public boolean hasPendingApproval(String sessionId)
        { return storeAdapter.hasPendingApproval(sessionId); }

    @Override public void deleteCheckpoint(String sessionId, String role)
        { storeAdapter.deleteCheckpoint(sessionId, role); }
}
