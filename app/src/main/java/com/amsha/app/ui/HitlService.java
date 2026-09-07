package com.amsha.app.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.CheckpointRecord;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.observability.AgentOTelConstants;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.tool.api.AgentSentinels;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/**
 * Service to manage Human-In-The-Loop approvals for the UI.
 */
@ApplicationScoped
public class HitlService {

    @Inject
    CheckpointStorePort checkpointStorePort;

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

        List<String> visibleSessionIds = visibleSessionIds(userId);

        if (visibleSessionIds.isEmpty()) {
            return Map.of();
        }

        List<CheckpointDto> list = checkpointStorePort.findByStatusAndSessionIds(
                CheckpointStatus.AWAITING_APPROVAL.name(), visibleSessionIds, effectiveLimit, effectiveOffset);

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

        List<String> visibleSessionIds = visibleSessionIds(userId);

        if (visibleSessionIds.isEmpty()) {
            return 0L;
        }

        return checkpointStorePort.countByStatusAndSessionIds(
                CheckpointStatus.AWAITING_APPROVAL.name(), visibleSessionIds);
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

    private List<String> visibleSessionIds(String userId) {
        List<String> visible = new ArrayList<>(heartbeatTracker.getSessionIdsByOwner(userId));
        visible.addAll(heartbeatTracker.getUnownedSessionIds());
        visible.removeIf(HitlService::isCronSession);
        return visible.stream().distinct().toList();
    }

    private static boolean isCronSession(String sessionId) {
        return sessionId != null && sessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX);
    }

    @Transactional(TxType.NOT_SUPPORTED)
    public void resolve(String taskId, String toolName, boolean approved, String reason, String requesterId) {
        CheckpointDto dto = findCheckpoint(taskId);
        if (dto == null) {
            throw new IllegalArgumentException("No pending approval found for ID: " + taskId);
        }

        AgentCheckpoint cp = CheckpointRecord.fromDto(dto);
        verifyRequester(cp, requesterId);
        if (isIdempotentReplay(cp, taskId, toolName, approved)) {
            return;
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
            if (isResolutionRace(e) && replayedAfterRace(cp, taskId, toolName, approved)) {
                if (span != null) span.setStatus(StatusCode.OK);
                return;
            }
            if (span != null) {
                span.setStatus(StatusCode.ERROR, e.getMessage());
                span.recordException(e);
            }
            throw e;
        } finally {
            if (span != null) span.end();
        }
    }

    private void verifyRequester(AgentCheckpoint cp, String requesterId) {
        if (isCronSession(cp.getSessionId())) {
            throw new SecurityException("Cron approvals must be resolved through the originating channel");
        }
        if (requesterId == null || requesterId.isBlank() || "anonymous".equals(requesterId)) {
            throw new SecurityException("Authenticated user identity is required");
        }
        Optional<String> owner = heartbeatTracker.getOwner(cp.getSessionId());
        if (owner != null && owner.isPresent()) {
            if (!requesterId.equals(owner.get())) {
                throw new SecurityException("Approval does not belong to the authenticated user");
            }
            return;
        }
        if (!heartbeatTracker.tryBindOwner(cp.getSessionId(), requesterId)) {
            throw new SecurityException("Approval session is no longer active");
        }
    }

    private CheckpointDto findCheckpoint(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Approval ID must not be blank");
        }
        CheckpointDto dto = null;
        try {
            Long id = Long.valueOf(taskId);
            dto = checkpointStorePort.findById(id).orElse(null);
        } catch (NumberFormatException _) {
            // taskId is not a numeric checkpoint ID; look up by sessionId
        }
        if (dto == null) {
            dto = checkpointStorePort.findLatestAwaitingApproval(taskId).orElse(null);
        }
        if (dto == null) {
            dto = checkpointStorePort.findLatestBySession(taskId).orElse(null);
        }
        return dto;
    }

    private boolean replayedAfterRace(AgentCheckpoint original, String taskId, String toolName, boolean approved) {
        return checkpointStorePort
                .findLatestBySession(original.getSessionId())
                .map(CheckpointRecord::fromDto)
                .map(latest -> isIdempotentReplay(latest, taskId, toolName, approved))
                .orElse(false);
    }

    private boolean isIdempotentReplay(AgentCheckpoint cp, String taskId, String toolName, boolean approved) {
        CheckpointStatus status = cp.getStatus();
        if (status == null) {
            throw unsafeApprovalState(taskId, "checkpoint has no status");
        }
        if (status == CheckpointStatus.AWAITING_APPROVAL) {
            return isAwaitingReplay(cp, taskId, toolName, approved);
        }
        if (status == CheckpointStatus.DENIED) {
            if (!approved) return true;
            throw contradictoryDecision(taskId, approved);
        }
        if (isAcceptedStatus(status)) {
            if (approved) return true;
            throw contradictoryDecision(taskId, approved);
        }
        if (status == CheckpointStatus.FAILED) {
            return isFailedReplay(cp, taskId, approved);
        }
        throw unsafeApprovalState(taskId, "checkpoint status is " + status);
    }

    private boolean isAwaitingReplay(AgentCheckpoint cp, String taskId, String toolName, boolean approved) {
        if (!cp.hasBatchToolDecisions()) {
            return false;
        }
        Map<String, ToolDecision> decisions = readBatchDecisions(cp, taskId);
        ToolDecision requested = approved ? ToolDecision.APPROVED : ToolDecision.DENIED;
        ToolDecision opposite = approved ? ToolDecision.DENIED : ToolDecision.APPROVED;
        if (toolName == null || toolName.isBlank()) {
            if (decisions.values().stream().anyMatch(opposite::equals)) {
                throw contradictoryDecision(taskId, approved);
            }
            return decisions.values().stream().allMatch(requested::equals);
        }
        ToolDecision existing = decisions.get(toolName);
        if (existing == null) {
            throw unsafeApprovalState(taskId, "requested tool is not in the checkpoint");
        }
        if (existing == requested) return true;
        if (existing == opposite) throw contradictoryDecision(taskId, approved);
        return false;
    }

    private boolean isFailedReplay(AgentCheckpoint cp, String taskId, boolean approved) {
        boolean denialReason = isDenialFailure(cp.getFailReason());
        if (cp.hasBatchToolDecisions()) {
            Map<String, ToolDecision> decisions = readBatchDecisions(cp, taskId);
            ToolDecision batchDecision = uniformFinalDecision(decisions);
            if (batchDecision == null) {
                throw unsafeApprovalState(taskId, "batch decisions are pending or contradictory");
            }
            if (denialReason && batchDecision != ToolDecision.DENIED) {
                throw unsafeApprovalState(taskId, "failure reason conflicts with batch decision");
            }
            if (batchDecision == (approved ? ToolDecision.APPROVED : ToolDecision.DENIED)) {
                return true;
            }
            throw contradictoryDecision(taskId, approved);
        }
        if (denialReason) {
            if (!approved) return true;
            throw contradictoryDecision(taskId, approved);
        }
        throw unsafeApprovalState(taskId, "failure cause does not identify the decision");
    }

    private ToolDecision uniformFinalDecision(Map<String, ToolDecision> decisions) {
        ToolDecision first = null;
        for (ToolDecision decision : decisions.values()) {
            if (decision == ToolDecision.PENDING) return null;
            if (first == null) {
                first = decision;
            } else if (first != decision) {
                return null;
            }
        }
        return first;
    }

    private Map<String, ToolDecision> readBatchDecisions(AgentCheckpoint cp, String taskId) {
        try {
            Map<String, ToolDecision> decisions = objectMapper.readValue(
                    cp.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
            if (decisions == null
                    || decisions.isEmpty()
                    || decisions.entrySet().stream()
                            .anyMatch(entry ->
                                    entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null)) {
                throw new IllegalArgumentException("empty or incomplete decision map");
            }
            return decisions;
        } catch (Exception e) {
            throw unsafeApprovalState(taskId, "batch decision state is invalid");
        }
    }

    private static boolean isAcceptedStatus(CheckpointStatus status) {
        return status == CheckpointStatus.APPROVED
                || status == CheckpointStatus.RESUMING
                || status == CheckpointStatus.IN_PROGRESS
                || status == CheckpointStatus.AWAITING_RETRY
                || status == CheckpointStatus.COMPLETED
                || status == CheckpointStatus.LOOP_EXCEEDED;
    }

    private static boolean isDenialFailure(String failReason) {
        if (failReason == null || failReason.isBlank()) return false;
        String normalized = failReason.toLowerCase(Locale.ROOT);
        return normalized.contains("denied") || normalized.contains("rejected");
    }

    private static boolean isResolutionRace(Exception error) {
        String message = error.getMessage();
        return message != null && (message.contains("already resolved") || message.contains("No pending checkpoint"));
    }

    private static IllegalStateException contradictoryDecision(String taskId, boolean approved) {
        String action = approved ? "approve" : "deny";
        return new IllegalStateException(
                "Approval decision already resolved for ID: " + taskId + "; cannot " + action + " it");
    }

    private static IllegalStateException unsafeApprovalState(String taskId, String detail) {
        return new IllegalStateException("Approval decision already resolved or cannot be determined safely for ID: "
                + taskId + " (" + detail + ")");
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
