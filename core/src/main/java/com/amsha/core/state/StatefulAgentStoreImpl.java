package com.amsha.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.CheckpointRecord;
import com.amsha.core.persistence.CheckpointMapper;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.CheckpointUpsertResult;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;

import org.jboss.logging.Logger;

/**
 * Domain-level orchestrator for agent checkpoint persistence.
 *
 * <p>Post-C2 decomposition: This class delegates three extracted
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
 * ({@link #claimForResume}, {@link #isBatchResuming}, {@link #clearBatchResuming})
 * remains on THIS class and THIS interface. External callers (WorkspaceManager,
 * CheckpointResumeHandler) MUST call through {@link StatefulAgentStore}, never
 * inject {@link ResumeCoordinationGuard} directly.
 */
@ApplicationScoped
public class StatefulAgentStoreImpl implements StatefulAgentStore {

    private static final Logger log = Logger.getLogger(StatefulAgentStoreImpl.class);

    private static final String UNKNOWN_CALLER = "unknown";

    @Inject
    CheckpointStorePort storeAdapter;

    @Inject
    CheckpointSerializer serializer;

    @Inject
    CheckpointTransitionService transitionService;

    @Inject
    ResumeCoordinationGuard resumeGuard;

    // ── Save / Load ───────────────────────────────────────────────────────────

    @Override
    public AgentCheckpoint save(
            String sessionId,
            String agentRole,
            String userId,
            AutonomyLevel autonomy,
            List<ReasoningStep> steps,
            String goal) {
        String json = serializer.serializeSteps(steps);
        Map<String, Object> contextData = WorkspaceContext.getData();
        String contextDataJson =
                (contextData == null || contextData.isEmpty()) ? null : serializer.serialize(contextData);
        CheckpointUpsertResult result = storeAdapter.upsertWithSteps(
                sessionId,
                agentRole,
                json,
                autonomy != null ? autonomy.toString() : AutonomyLevel.SUPERVISED.toString(),
                steps.size(),
                goal,
                contextDataJson);

        // §Fix: Ensure granular steps are also saved
        saveSteps(result.checkpoint().id(), steps);

        if (result.created()) {
            transitionService.emitCreated(
                    sessionId,
                    agentRole,
                    userId,
                    autonomy,
                    result.checkpoint().id(),
                    result.checkpoint().createdAt());
        }
        return CheckpointRecord.fromDto(result.checkpoint());
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        CheckpointDto saved = storeAdapter.save(CheckpointMapper.toDto(checkpoint));
        return CheckpointRecord.fromDto(saved);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<AgentCheckpoint> load(String sessionId, String agentRole) {
        log.debugf("[Store] load(session=%s, role=%s)", sessionId, agentRole);
        Optional<AgentCheckpoint> result =
                storeAdapter.findBySessionAndRole(sessionId, agentRole).map(CheckpointRecord::fromDto);
        log.debugf(
                "[Store] load returned %s for session=%s role=%s",
                result.isPresent() ? "checkpoint" : "empty", sessionId, agentRole);
        return result;
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
        List<ReasoningStepDto> dtos = steps.stream().map(serializer::toDto).toList();
        storeAdapter.saveSteps(checkpointId, dtos);
    }

    @Override
    public AgentCheckpoint saveWithSteps(AgentCheckpoint checkpoint, List<ReasoningStep> steps) {
        List<ReasoningStepDto> dtos = steps == null
                ? List.of()
                : steps.stream().map(serializer::toDto).toList();
        CheckpointDto saved = storeAdapter.saveCheckpointWithSteps(CheckpointMapper.toDto(checkpoint), dtos);
        return CheckpointRecord.fromDto(saved);
    }

    @Override
    public AgentCheckpoint saveAndFinalize(
            String sessionId,
            String agentRole,
            String userId,
            AutonomyLevel autonomy,
            List<ReasoningStep> steps,
            CheckpointStatus finalStatus,
            String goal) {
        String json = serializer.serializeSteps(steps);
        Map<String, Object> contextData = WorkspaceContext.getData();
        String contextDataJson =
                (contextData == null || contextData.isEmpty()) ? null : serializer.serialize(contextData);
        List<ReasoningStepDto> stepDtos = steps.stream().map(serializer::toDto).toList();

        CheckpointUpsertResult result = storeAdapter.upsertAndFinalize(
                sessionId, agentRole, json, finalStatus.name(), stepDtos, goal, contextDataJson);

        CheckpointDto saved = result.checkpoint();
        boolean isNew = result.created();

        if (isNew) {
            transitionService.emitCreated(sessionId, agentRole, userId, autonomy, saved.id(), saved.createdAt());
        }
        transitionService.emitFinalStatus(sessionId, agentRole, userId, finalStatus, saved.id(), saved.createdAt());
        return CheckpointRecord.fromDto(saved);
    }

    @Override
    public List<ReasoningStep> loadSteps(Long checkpointId) {
        return storeAdapter.loadSteps(checkpointId).stream()
                .map(serializer::fromDto)
                .toList();
    }

    // ── Status transitions (all delegated to CheckpointTransitionService) ─────

    @Override
    public void markFailed(Long id, String reason) {
        transitionService.markFailed(id, reason, null, null, null, UNKNOWN_CALLER);
    }

    @Override
    public void markFailed(AgentCheckpoint cp, String reason) {
        transitionService.markFailed(cp.getId(), reason, null, null, null, UNKNOWN_CALLER);
    }

    @Override
    public void markFailed(Long id, String reason, String errorCode, String traceId, String rootCauseClass) {
        transitionService.markFailed(id, reason, errorCode, traceId, rootCauseClass, UNKNOWN_CALLER);
    }

    @Override
    public void markFailed(AgentCheckpoint cp, String reason, String errorCode, String traceId, String rootCauseClass) {
        transitionService.markFailed(cp.getId(), reason, errorCode, traceId, rootCauseClass, UNKNOWN_CALLER);
    }

    @Override
    public void markOrphaned(Long checkpointId, String reason) {
        transitionService.markOrphaned(checkpointId, reason, UNKNOWN_CALLER);
    }

    @Override
    public void markCompleted(Long checkpointId) {
        transitionService.markCompleted(checkpointId, UNKNOWN_CALLER);
    }

    @Override
    public void archive(Long checkpointId) {
        storeAdapter.archive(checkpointId);
    }

    @Override
    public void stampChannelInfo(Long checkpointId, String channelType, String channelPlatformId) {
        storeAdapter.stampChannelInfo(checkpointId, channelType, channelPlatformId);
    }

    // ── Resume guard (§3.4 / §7.29 - all delegated, public API stays here) ───

    /**
     * Delegates to {@link CheckpointStorePort#claimForResume} for distributed safety.
     * Also maintains an in-memory mark in {@link ResumeCoordinationGuard} for
     * instance-local backpressure.
     */
    @Override
    public boolean claimForResume(String sessionId, String agentRole) {
        return claimForResume(sessionId, agentRole, false);
    }

    @Override
    public boolean claimForResume(String sessionId, String agentRole, boolean hasBrowserTool) {
        // 1. Instance-local check (fast path)
        if (resumeGuard.isResuming(sessionId)) {
            log.warnf(
                    "[Store] claimForResume: instance-local guard blocked resume for session=%s role=%s",
                    sessionId, agentRole);
            return false;
        }

        // 2. Distributed DB-backed lock (§T5-4)
        // Transition AWAITING_APPROVAL -> RESUMING in SQLite
        boolean claimed = storeAdapter.claimForResume(sessionId, agentRole);
        log.infof(
                "[Store] claimForResume(session=%s, role=%s, hasBrowser=%s) -> claimed=%s",
                sessionId, agentRole, hasBrowserTool, claimed);
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
    @Override
    public boolean isBatchResuming(String sessionId) {
        return resumeGuard.isResuming(sessionId);
    }

    /**
     * §3.4 / §7.29 (MUST): MUST be called in the finally block of the resume
     * path and by WorkspaceManager.terminateSession(). Never suppress.
     */
    @Override
    public void clearBatchResuming(String sessionId) {
        resumeGuard.clearResuming(sessionId);
    }

    @Override
    public void clearResuming(String sessionId) {
        resumeGuard.clearResuming(sessionId);
    }

    @Override
    public void releaseResumeClaim(String sessionId, String agentRole) {
        log.infof("[Store] releaseResumeClaim(session=%s, role=%s)", sessionId, agentRole);
        storeAdapter.releaseResumeClaim(sessionId, agentRole);
        resumeGuard.clearResuming(sessionId);
    }

    // ── Batch tool decisions ──────────────────────────────────────────────────

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public boolean updateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision) {
        log.infof(
                "[Store] updateBatchToolDecision(session=%s, role=%s, tool=%s, decision=%s)",
                sessionId, agentRole, toolName, decision);
        boolean result = storeAdapter.atomicUpdateBatchToolDecision(sessionId, agentRole, toolName, decision);
        log.infof("[Store] updateBatchToolDecision returned %s for session=%s tool=%s", result, sessionId, toolName);
        return result;
    }

    @Override
    public boolean isAllBatchToolsDecided(String sessionId, String agentRole) {
        // Use findLatestAwaitingApproval to get fresh data
        // after writes from BatchOps (JDBI has no first-level cache)
        Optional<CheckpointDto> dtoOpt =
                storeAdapter.findLatestAwaitingApproval(sessionId).filter(dto -> agentRole.equals(dto.agentRole()));
        if (dtoOpt.isEmpty()) {
            log.warnf(
                    "[Store] isAllBatchToolsDecided: no checkpoint found for session=%s role=%s", sessionId, agentRole);
            return false;
        }
        if (dtoOpt.get().batchToolDecisions() == null) {
            log.warnf(
                    "[Store] isAllBatchToolsDecided: batchToolDecisions is null for session=%s role=%s",
                    sessionId, agentRole);
            return false;
        }
        String rawJson = dtoOpt.get().batchToolDecisions();
        Map<String, ToolDecision> map =
                serializer.deserialize(rawJson, new TypeReference<Map<String, ToolDecision>>() {});
        if (map == null) {
            log.warnf("[Store] isAllBatchToolsDecided: deserialized map is null for session=%s", sessionId);
            return false;
        }
        long pendingCount =
                map.values().stream().filter(v -> ToolDecision.PENDING == v).count();
        boolean allDecided = pendingCount == 0;
        log.infof(
                "[Store] isAllBatchToolsDecided(session=%s, role=%s): map=%s pending=%d allDecided=%s",
                sessionId, agentRole, rawJson, pendingCount, allDecided);
        return allDecided;
    }

    // ── Query delegation ──────────────────────────────────────────────────────

    @Override
    public Optional<AgentCheckpoint> findLatestAwaitingApproval(String sessionId) {
        return storeAdapter.findLatestAwaitingApproval(sessionId).map(CheckpointRecord::fromDto);
    }

    @Override
    public Optional<AgentCheckpoint> findLatestAwaitingApprovalBySessionContaining(String segment) {
        return storeAdapter
                .findLatestAwaitingApprovalBySessionContaining(segment)
                .map(CheckpointRecord::fromDto);
    }

    @Override
    public Optional<AgentCheckpoint> findLatestCheckpoint(String sessionId) {
        return storeAdapter.findLatestBySession(sessionId).map(CheckpointRecord::fromDto);
    }

    @Override
    public Optional<AgentCheckpoint> findLatestNonArchivedCheckpoint(String sessionId) {
        return storeAdapter.findLatestNonArchivedBySession(sessionId).map(CheckpointRecord::fromDto);
    }

    @Override
    public Optional<AgentCheckpoint> findLatestByStatus(String sessionId, CheckpointStatus status) {
        return storeAdapter
                .findLatestBySessionAndStatus(sessionId, status.name())
                .map(CheckpointRecord::fromDto);
    }

    @Override
    public Optional<String> findLatestRole(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return storeAdapter.findLatestNonArchivedBySession(sessionId).map(dto -> dto.agentRole());
    }

    @Override
    public Optional<AutonomyLevel> loadLatestAutonomyForSession(String sessionId) {
        return storeAdapter
                .findLatestBySession(sessionId)
                .flatMap(d -> Optional.ofNullable(d.autonomyLevel()))
                .map(AutonomyLevel::valueOf);
    }

    @Override
    public List<AgentCheckpoint> findStaleAwaitingApproval(Instant cutoff) {
        return storeAdapter.findStaleApprovals(cutoff).stream()
                .<AgentCheckpoint>map(CheckpointRecord::fromDto)
                .toList();
    }

    @Override
    public List<AgentCheckpoint> findByStatus(CheckpointStatus status) {
        return storeAdapter.findByStatus(status.toString()).stream()
                .<AgentCheckpoint>map(CheckpointRecord::fromDto)
                .toList();
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

    @Override
    public boolean hasPendingApprovalForChat(String chatId) {
        return storeAdapter.hasPendingApprovalForChat(chatId);
    }

    @Override
    public void deleteCheckpoint(String sessionId, String role) {
        storeAdapter.deleteCheckpoint(sessionId, role);
    }
}
