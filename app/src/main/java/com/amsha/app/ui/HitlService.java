package com.amsha.app.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.CheckpointRecord;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.observability.AgentOTelConstants;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.persistence.api.CheckpointDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Service to manage Human-In-The-Loop approvals for the UI.
 * Inactive when no database backend is available (e.g., filesystem mode).
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkus.flyway.enabled", stringValue = "true", enableIfMissing = true)
public class HitlService {

    @Inject
    Jdbi jdbi;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    CheckpointApprovalService checkpointApprovalService;

    @Inject
    AgentOtelContext otelContext;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    private static final int MAX_LIMIT = 50;

    /**
     * Returns pending approvals scoped to the given userId.
     * <p>
     * F27/§85-A: The old unfiltered query exposed all users' pending approvals.
     * Now uses {@link SessionHeartbeatTracker#getSessionIdsByOwner(String)} to
     * restrict results to sessions owned by the requesting user only.
     *
     * @param userId  trusted user identity (from API key, never from LLM)
     * @param limit   max results to return (capped at {@value #MAX_LIMIT})
     * @param offset  number of results to skip for pagination
     */
    public Map<String, PendingApproval> getPendingApprovals(String userId, int limit, int offset) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        int effectiveLimit = Math.min(Math.max(1, limit), MAX_LIMIT);
        int effectiveOffset = Math.max(0, offset);

        List<String> ownedSessionIds = heartbeatTracker.getSessionIdsByOwner(userId);
        List<String> unownedSessionIds = heartbeatTracker.getUnownedSessionIds();

        List<String> visibleSessionIds = new ArrayList<>(ownedSessionIds);
        visibleSessionIds.addAll(unownedSessionIds);

        if (visibleSessionIds.isEmpty()) {
            return Map.of();
        }

        List<CheckpointDto> list =
                jdbi.withHandle(handle -> handle.createQuery("SELECT " + "  checkpoint_id AS \"id\", "
                                + "  session_id AS \"sessionId\", "
                                + "  agent_role AS \"agentRole\", "
                                + "  status, "
                                + "  autonomy_level AS \"autonomyLevel\", "
                                + "  state_json AS \"stateJson\", "
                                + "  channel_platform_id AS \"channelPlatformId\", "
                                + "  channel_type AS \"channelType\", "
                                + "  original_goal AS \"originalGoal\", "
                                + "  failed_at AS \"failedAt\", "
                                + "  completed_at AS \"completedAt\", "
                                + "  fail_reason AS \"failReason\", "
                                + "  pending_tool_name AS \"pendingToolName\", "
                                + "  batch_tool_decisions AS \"batchToolDecisions\", "
                                + "  pending_tool_parameters AS \"pendingToolParametersJson\", "
                                + "  pre_executed_results AS \"preExecutedResults\", "
                                + "  max_iterations AS \"maxIterations\", "
                                + "  iterations_used AS \"iterationsUsed\", "
                                + "  retry_after_ms AS \"retryAfterMs\", "
                                + "  system_note AS \"systemNote\", "
                                + "  context_data AS \"contextDataJson\", "
                                + "  error_code AS \"errorCode\", "
                                + "  trace_id AS \"traceId\", "
                                + "  root_cause_class AS \"rootCauseClass\", "
                                + "  parent_checkpoint_id AS \"parentCheckpointId\", "
                                + "  depth, "
                                + "  created_at AS \"createdAt\", "
                                + "  updated_at AS \"updatedAt\" "
                                + "FROM agent_checkpoint "
                                + "WHERE status = :status "
                                + "  AND session_id IN (<sessionIds>) "
                                + "ORDER BY checkpoint_id DESC "
                                + "LIMIT :limit OFFSET :offset")
                        .bind("status", CheckpointStatus.AWAITING_APPROVAL.name())
                        .bindList("sessionIds", visibleSessionIds)
                        .bind("limit", effectiveLimit)
                        .bind("offset", effectiveOffset)
                        .registerRowMapper(ConstructorMapper.factory(CheckpointDto.class))
                        .mapTo(CheckpointDto.class)
                        .list());

        return list.stream()
                .map(CheckpointRecord::fromDto)
                .map(this::mapToPending)
                .collect(Collectors.toMap(PendingApproval::taskId, p -> p));
    }

    /**
     * Returns total count of pending approvals for the given userId.
     */
    public long countPendingApprovals(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }

        List<String> ownedSessionIds = heartbeatTracker.getSessionIdsByOwner(userId);
        List<String> unownedSessionIds = heartbeatTracker.getUnownedSessionIds();

        List<String> visibleSessionIds = new ArrayList<>(ownedSessionIds);
        visibleSessionIds.addAll(unownedSessionIds);

        if (visibleSessionIds.isEmpty()) {
            return 0L;
        }

        return jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM agent_checkpoint "
                        + "WHERE status = :status " + "  AND session_id IN (<sessionIds>)")
                .bind("status", CheckpointStatus.AWAITING_APPROVAL.name())
                .bindList("sessionIds", visibleSessionIds)
                .mapTo(Long.class)
                .one());
    }

    private PendingApproval mapToPending(AgentCheckpoint cp) {
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(
                    cp.getPendingToolParametersJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            params = Map.of();
        }
        return new PendingApproval(
                cp.getId().toString(),
                cp.getSessionId(),
                cp.getPendingToolName(),
                params,
                cp.getUpdatedAt(),
                cp.getAgentRole());
    }

    @Transactional(TxType.NOT_SUPPORTED)
    public void resolve(String taskId, String toolName, boolean approved, String reason) {
        Long id = Long.valueOf(taskId);
        CheckpointDto dto = jdbi.withHandle(handle -> handle.createQuery("SELECT " + "  checkpoint_id AS \"id\", "
                        + "  session_id AS \"sessionId\", "
                        + "  agent_role AS \"agentRole\", "
                        + "  status, "
                        + "  autonomy_level AS \"autonomyLevel\", "
                        + "  state_json AS \"stateJson\", "
                        + "  channel_platform_id AS \"channelPlatformId\", "
                        + "  channel_type AS \"channelType\", "
                        + "  original_goal AS \"originalGoal\", "
                        + "  failed_at AS \"failedAt\", "
                        + "  completed_at AS \"completedAt\", "
                        + "  fail_reason AS \"failReason\", "
                        + "  pending_tool_name AS \"pendingToolName\", "
                        + "  batch_tool_decisions AS \"batchToolDecisions\", "
                        + "  pending_tool_parameters AS \"pendingToolParametersJson\", "
                        + "  pre_executed_results AS \"preExecutedResults\", "
                        + "  max_iterations AS \"maxIterations\", "
                        + "  iterations_used AS \"iterationsUsed\", "
                        + "  retry_after_ms AS \"retryAfterMs\", "
                        + "  system_note AS \"systemNote\", "
                        + "  context_data AS \"contextDataJson\", "
                        + "  error_code AS \"errorCode\", "
                        + "  trace_id AS \"traceId\", "
                        + "  root_cause_class AS \"rootCauseClass\", "
                        + "  parent_checkpoint_id AS \"parentCheckpointId\", "
                        + "  depth, "
                        + "  created_at AS \"createdAt\", "
                        + "  updated_at AS \"updatedAt\" "
                        + "FROM agent_checkpoint "
                        + "WHERE checkpoint_id = :id")
                .bind("id", id)
                .registerRowMapper(ConstructorMapper.factory(CheckpointDto.class))
                .mapTo(CheckpointDto.class)
                .findOne()
                .orElse(null));

        if (dto == null) {
            throw new IllegalArgumentException("No pending approval found for ID: " + taskId);
        }

        AgentCheckpoint cp = CheckpointRecord.fromDto(dto);
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("Already resolved for ID: " + taskId);
        }

        Span span = otelContext.childSpan(cp.getSessionId(), AgentOTelConstants.SPAN_HITL_RESOLVE);
        try {
            if (span != null) {
                span.setAttribute(AgentOTelConstants.ATTR_HITL_TASK_ID, taskId);
                span.setAttribute(AgentOTelConstants.ATTR_HITL_TOOL_NAME, toolName != null ? toolName : "all");
                span.setAttribute(AgentOTelConstants.ATTR_HITL_APPROVED, approved);
            }

            AgentCheckpoint resolvedCp = executeResolveDecision(cp, approved, toolName, reason);

            if (resolvedCp != null) {
                checkpointApprovalService.triggerResume(resolvedCp);
            }

            if (span != null) span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR, e.getMessage());
                span.recordException(e);
            }
            throw e;
        } finally {
            if (span != null) span.end();
        }
    }

    private AgentCheckpoint executeResolveDecision(
            AgentCheckpoint cp, boolean approved, String toolName, String reason) {
        if (approved) {
            if (toolName != null && !toolName.isBlank()) {
                return checkpointApprovalService.approveTool(cp.getSessionId(), toolName, reason);
            }
            List<String> pending = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
            if (pending.size() == 1) {
                return checkpointApprovalService.approveTool(cp.getSessionId(), pending.get(0), reason);
            }
            return checkpointApprovalService.approveAll(cp.getSessionId(), reason);
        } else {
            if (toolName != null && !toolName.isBlank()) {
                return checkpointApprovalService.denyTool(cp.getSessionId(), toolName, reason);
            }
            List<String> pending = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
            if (pending.size() == 1) {
                return checkpointApprovalService.denyTool(cp.getSessionId(), pending.get(0), reason);
            }
            return checkpointApprovalService.denyAll(cp.getSessionId(), reason);
        }
    }

    public record PendingApproval(
            String taskId,
            String sessionId,
            String toolName,
            Map<String, Object> params,
            Instant requestedAt,
            String agentRole) {}
}
