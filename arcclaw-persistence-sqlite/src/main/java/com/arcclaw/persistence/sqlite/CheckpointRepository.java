package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.util.TemporalUtils;
import com.arcclaw.persistence.common.entity.AgentCheckpointEntity;
import org.jboss.logging.Logger;

/**
 * Handles core JPA operations for AgentCheckpointEntity entities.
 */
@ApplicationScoped
public class CheckpointRepository {

    private static final Logger log = Logger.getLogger(CheckpointRepository.class);

    private final EntityManager em;

    @Inject
    public CheckpointRepository(EntityManager em) {
        this.em = em;
    }

    protected CheckpointRepository() {
        this(null);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public AgentCheckpointEntity persist(AgentCheckpointEntity entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public AgentCheckpointEntity merge(AgentCheckpointEntity entity) {
        AgentCheckpointEntity merged = em.merge(entity);
        em.flush();
        return merged;
    }

    /**
     * Native SQL full-row update — bypasses JPA persistence context.
     * JPA em.merge() silently fails to commit through Narayana JTA with SQLite.
     * Native SQL executeUpdate() works reliably (proven by CheckpointStatusService).
     */
    @Transactional(Transactional.TxType.REQUIRED)
    public void nativeUpdate(AgentCheckpointEntity e) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        int updated = em.createNativeQuery(
                "UPDATE agent_checkpoint SET " +
                "status = :status, " +
                "autonomy_level = :autonomy, " +
                "state_json = :stateJson, " +
                "channel_platform_id = :channelPlatformId, " +
                "channel_type = :channelType, " +
                "original_goal = :originalGoal, " +
                "failed_at = :failedAt, " +
                "completed_at = :completedAt, " +
                "fail_reason = :failReason, " +
                "pending_tool_name = :pendingToolName, " +
                "pending_tool_role = :pendingToolRole, " +
                "batch_tool_decisions = :batchDecisions, " +
                "pending_tool_parameters = :pendingParams, " +
                "pre_executed_results = :preExec, " +
                "max_iterations = :maxIter, " +
                "iterations_used = :iterUsed, " +
                "retry_after_ms = :retryMs, " +
                "system_note = :sysNote, " +
                "context_data = :ctxData, " +
                "error_code = :errCode, " +
                "trace_id = :traceId, " +
                "root_cause_class = :rootCause, " +
                "parent_checkpoint_id = :parentId, " +
                "depth = :depth, " +
                "version = version + 1, " +
                "updated_at = :now " +
                "WHERE checkpoint_id = :id")
                .setParameter("status", e.getStatus() != null ? e.getStatus().name() : null)
                .setParameter("autonomy", e.getAutonomyLevel() != null ? e.getAutonomyLevel().name() : null)
                .setParameter("stateJson", e.getStateJson())
                .setParameter("channelPlatformId", e.getChannelPlatformId())
                .setParameter("channelType", e.getChannelType())
                .setParameter("originalGoal", e.getOriginalGoal())
                .setParameter("failedAt", e.getFailedAt() != null ? TemporalUtils.formatIsoLex(e.getFailedAt()) : null)
                .setParameter("completedAt", e.getCompletedAt() != null ? TemporalUtils.formatIsoLex(e.getCompletedAt()) : null)
                .setParameter("failReason", e.getFailReason())
                .setParameter("pendingToolName", e.getPendingToolName())
                .setParameter("pendingToolRole", e.getPendingToolRole())
                .setParameter("batchDecisions", e.getBatchToolDecisions())
                .setParameter("pendingParams", e.getPendingToolParametersJson())
                .setParameter("preExec", e.getPreExecutedResults())
                .setParameter("maxIter", e.getMaxIterations())
                .setParameter("iterUsed", e.getIterationsUsed())
                .setParameter("retryMs", e.getRetryAfterMs())
                .setParameter("sysNote", e.getSystemNote())
                .setParameter("ctxData", e.getContextDataJson())
                .setParameter("errCode", e.getErrorCode())
                .setParameter("traceId", e.getTraceId())
                .setParameter("rootCause", e.getRootCauseClass())
                .setParameter("parentId", e.getParentCheckpointId())
                .setParameter("depth", e.getDepth())
                .setParameter("now", now)
                .setParameter("id", e.getId())
                .executeUpdate();
        log.infof("[Repo] nativeUpdate: checkpoint_id=%d status=%s updated=%d", e.getId(), e.getStatus(), updated);
        // Clear persistence context so subsequent JPA reads see the native SQL changes
        em.clear();
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void stampChannelInfo(Long checkpointId, String channelType, String channelPlatformId) {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        int updated = em.createNativeQuery(
                "UPDATE agent_checkpoint SET " +
                "channel_type = :type, " +
                "channel_platform_id = :platform, " +
                "updated_at = :now, " +
                "version = version + 1 " +
                "WHERE checkpoint_id = :id")
                .setParameter("type", channelType)
                .setParameter("platform", channelPlatformId)
                .setParameter("now", now)
                .setParameter("id", checkpointId)
                .executeUpdate();
        log.infof("[Repo] stampChannelInfo: checkpoint_id=%d updated=%d", checkpointId, updated);
        em.clear();
    }

    public Optional<AgentCheckpointEntity> findById(Long id) {
        em.clear();
        return Optional.ofNullable(em.find(AgentCheckpointEntity.class, id));
    }

    public List<AgentCheckpointEntity> findBySessionAndRole(String sessionId, String agentRole) {
        log.debugf("[Query] findBySessionAndRole(session=%s, role=%s)", sessionId, agentRole);
        List<AgentCheckpointEntity> result = em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.agentRole = :role " +
                "  AND c.status <> :archived " +
                "ORDER BY c.id DESC",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setParameter("archived", CheckpointStatus.ARCHIVED)
                .setMaxResults(1)
                .getResultList();
        log.debugf("[Query] findBySessionAndRole returned %d result(s) for session=%s role=%s", result.size(), sessionId, agentRole);
        return result;
    }

    public List<AgentCheckpointEntity> findAnyBySessionAndRole(String sessionId, String agentRole) {
        log.debugf("[Query] findAnyBySessionAndRole(session=%s, role=%s)", sessionId, agentRole);
        List<AgentCheckpointEntity> result = em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.agentRole = :role " +
                "ORDER BY c.id DESC",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setMaxResults(1)
                .getResultList();
        log.debugf("[Query] findAnyBySessionAndRole returned %d result(s) for session=%s role=%s", result.size(), sessionId, agentRole);
        return result;
    }

    public List<AgentCheckpointEntity> findActiveBySession(String sessionId) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.status NOT IN (:terminalStatuses) " +
                "ORDER BY c.id ASC",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                .getResultList();
    }

    public List<AgentCheckpointEntity> findLatestAwaiting(String sessionId) {
        log.debugf("[Query] findLatestAwaiting(session=%s)", sessionId);
        em.clear();
        List<AgentCheckpointEntity> result = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid " +
                "  AND c.status IN (:s1, :s2, :s3) " +
                "ORDER BY c.id DESC",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setParameter("s1", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("s2", CheckpointStatus.RESUMING)
                .setParameter("s3", CheckpointStatus.IN_PROGRESS)
                .setMaxResults(1)
                .getResultList();
        log.debugf("[Query] findLatestAwaiting returned %d result(s) for session=%s", result.size(), sessionId);
        if (!result.isEmpty()) {
            log.debugf("[Query] findLatestAwaiting: session=%s status=%s id=%d", sessionId, result.get(0).getStatus(), result.get(0).getId());
        }
        return result;
    }

    public List<AgentCheckpointEntity> findByStatus(String status) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status ORDER BY c.id DESC",
                AgentCheckpointEntity.class)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .getResultList();
    }

    public List<AgentCheckpointEntity> findAllActive() {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status NOT IN :terminal ORDER BY c.updatedAt DESC",
                AgentCheckpointEntity.class)
                .setParameter("terminal", CheckpointStatus.terminalStatuses())
                .getResultList();
    }

    public List<AgentCheckpointEntity> findLatestBySession(String sessionId) {
        log.debugf("[Query] findLatestBySession(session=%s)", sessionId);
        List<AgentCheckpointEntity> result = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid ORDER BY c.id DESC",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setMaxResults(1)
                .getResultList();
        log.debugf("[Query] findLatestBySession returned %d result(s) for session=%s", result.size(), sessionId);
        return result;
    }

    public List<AgentCheckpointEntity> findLatestNonArchived(String sessionId) {
        log.debugf("[Query] findLatestNonArchived(session=%s)", sessionId);
        List<AgentCheckpointEntity> result = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status <> :archived ORDER BY c.id DESC",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setParameter("archived", CheckpointStatus.ARCHIVED)
                .setMaxResults(1)
                .getResultList();
        log.debugf("[Query] findLatestNonArchived returned %d result(s) for session=%s", result.size(), sessionId);
        return result;
    }

    public List<AgentCheckpointEntity> findLatestBySessionAndStatus(String sessionId, String status) {
        log.debugf("[Query] findLatestBySessionAndStatus(session=%s, status=%s)", sessionId, status);
        List<AgentCheckpointEntity> result = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status = :status ORDER BY c.id DESC",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .setMaxResults(1)
                .getResultList();
        log.debugf("[Query] findLatestBySessionAndStatus returned %d result(s) for session=%s status=%s", result.size(), sessionId, status);
        return result;
    }

    public List<AgentCheckpointEntity> findLatestAwaitingBySegment(String segment) {
        log.debugf("[Query] findLatestAwaitingBySegment(segment=%s)", segment);
        em.clear();
        List<AgentCheckpointEntity> result = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId LIKE :segment " +
                "  AND c.status IN (:s1, :s2, :s3) " +
                "ORDER BY c.id DESC",
                AgentCheckpointEntity.class)
                .setParameter("segment", "%" + segment + "%")
                .setParameter("s1", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("s2", CheckpointStatus.RESUMING)
                .setParameter("s3", CheckpointStatus.IN_PROGRESS)
                .setMaxResults(1)
                .getResultList();
        log.debugf("[Query] findLatestAwaitingBySegment returned %d result(s) for segment=%s", result.size(), segment);
        if (!result.isEmpty()) {
            log.debugf("[Query] findLatestAwaitingBySegment: segment=%s resolved to session=%s", segment, result.get(0).getSessionId());
        }
        return result;
    }

    public boolean hasPendingApprovalForChat(String chatId) {
        if (chatId == null) return false;
        em.clear();
        return !em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.channelPlatformId = :chatId AND c.status = :s AND c.sessionId NOT LIKE 'cron-%'",
                AgentCheckpointEntity.class)
                .setParameter("chatId", chatId)
                .setParameter("s", CheckpointStatus.AWAITING_APPROVAL)
                .getResultList()
                .isEmpty();
    }
}

