package com.amsha.core.api;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.loop.CheckpointResolutionException;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.api.dto.ServiceResult;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;

import org.jboss.logging.Logger;

/** Handles REST approval decisions and their transaction-free resume boundary. */
final class AgentSessionApprovalHandler {

    private static final String REST_USER_NAME = ApiConstants.REST_USER_NAME;
    private static final String REST_API_SESSION_PREFIX = "rest-api-";
    private static final String REST_CHAT_SESSION_PREFIX = "rest-chat-";

    private final AgentLoop agentLoop;
    private final ReasoningEngine reasoningEngine;
    private final StatefulAgentStore agentStore;
    private final AgentResponseMapper responseMapper;
    private final CheckpointResumeHandler checkpointResumeHandler;
    private final CheckpointApprovalService checkpointApprovalService;
    private final AuditService auditService;
    private final ActiveWorkspaceContext activeWorkspaceContext;
    private final int maxResumeIterations;

    AgentSessionApprovalHandler(
            AgentLoop agentLoop,
            ReasoningEngine reasoningEngine,
            StatefulAgentStore agentStore,
            AgentResponseMapper responseMapper,
            CheckpointResumeHandler checkpointResumeHandler,
            CheckpointApprovalService checkpointApprovalService,
            AuditService auditService,
            ActiveWorkspaceContext activeWorkspaceContext,
            int maxResumeIterations) {
        this.agentLoop = agentLoop;
        this.reasoningEngine = reasoningEngine;
        this.agentStore = agentStore;
        this.responseMapper = responseMapper;
        this.checkpointResumeHandler = checkpointResumeHandler;
        this.checkpointApprovalService = checkpointApprovalService;
        this.auditService = auditService;
        this.activeWorkspaceContext = activeWorkspaceContext;
        this.maxResumeIterations = maxResumeIterations;
    }

    ServiceResult<Object> approve(String sessionId, String userId, String agentRole) {
        try {
            return withSelectedWorkspace(
                    () -> WorkspaceScopeHolder.call(sessionId, () -> approveInScope(sessionId, userId, agentRole)));
        } catch (CheckpointResolutionException e) {
            logRace("Approve", sessionId, e);
            return ServiceResult.error(resolutionErrorCode(e), e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error("SYSTEM", e.getMessage() != null ? e.getMessage() : "Internal error");
        }
    }

    ServiceResult<Object> deny(String sessionId, String userId, String agentRole) {
        try {
            return withSelectedWorkspace(
                    () -> WorkspaceScopeHolder.call(sessionId, () -> denyInScope(sessionId, userId, agentRole)));
        } catch (CheckpointResolutionException e) {
            logRace("Deny", sessionId, e);
            return ServiceResult.error(resolutionErrorCode(e), e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error("SYSTEM", e.getMessage() != null ? e.getMessage() : "Internal error");
        }
    }

    private ServiceResult<Object> approveInScope(String sessionId, String userId, String agentRole) throws Exception {
        String canonicalRole = AgentRoleEnum.fromString(agentRole).toScopedName();
        var checkpoint = agentStore.load(sessionId, canonicalRole);
        if (checkpoint.isEmpty()) {
            return noPendingApproval();
        }
        if (checkpoint.get().getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
            if (checkpoint.get().getStatus() == CheckpointStatus.IN_PROGRESS
                    && cascadeApprove(sessionId, userId, canonicalRole)) {
                return ServiceResult.success(Map.of("status", CheckpointStatus.APPROVED.name(), "session", sessionId));
            }
            return noPendingApproval();
        }

        try {
            checkpointApprovalService.approveAll(sessionId, "Human approved via REST");
        } catch (CheckpointResolutionException alreadyResolved) {
            if (!isAlreadyResolved(alreadyResolved)) throw alreadyResolved;
            if (!cascadeApprove(sessionId, userId, canonicalRole)) throw alreadyResolved;
        }
        auditService.recordMandatory(
                sessionId, userId, canonicalRole, SecurityEventType.APPROVAL_GRANTED, "via=REST role=" + agentRole);
        return resume(sessionId, userId, agentRole, canonicalRole, checkpoint.get());
    }

    private ServiceResult<Object> denyInScope(String sessionId, String userId, String agentRole) throws Exception {
        String canonicalRole = AgentRoleEnum.fromString(agentRole).toScopedName();
        var checkpoint = agentStore.load(sessionId, canonicalRole);
        if (checkpoint.isEmpty()) {
            return noPendingApproval();
        }
        if (checkpoint.get().getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
            if (checkpoint.get().getStatus() == CheckpointStatus.IN_PROGRESS
                    && cascadeDeny(sessionId, userId, canonicalRole)) {
                return ServiceResult.success(Map.of("status", CheckpointStatus.DENIED.name(), "session", sessionId));
            }
            return noPendingApproval();
        }

        AgentCheckpoint resolved;
        try {
            resolved = checkpointApprovalService.denyAll(sessionId, "Human denied via REST");
        } catch (CheckpointResolutionException alreadyResolved) {
            if (!isAlreadyResolved(alreadyResolved)) throw alreadyResolved;
            if (!cascadeDeny(sessionId, userId, canonicalRole)) throw alreadyResolved;
            resolved = checkpoint.get();
        }
        auditService.recordMandatory(
                sessionId, userId, canonicalRole, SecurityEventType.APPROVAL_DENIED, "via=REST role=" + agentRole);
        checkpointApprovalService.triggerResume(resolved);
        return ServiceResult.success(Map.of("status", ToolDecision.DENIED.name(), "session", sessionId));
    }

    private boolean cascadeApprove(String sessionId, String userId, String canonicalRole)
            throws CheckpointResolutionException {
        int cascaded = new DelegateApprovalCascade(agentStore, checkpointApprovalService, auditService)
                .approveAwaitingDelegates(sessionId, userId, canonicalRole);
        if (cascaded > 0) {
            auditService.recordMandatory(
                    sessionId, userId, canonicalRole, SecurityEventType.APPROVAL_GRANTED, "via=REST delegated-child");
            return true;
        }
        return false;
    }

    private boolean cascadeDeny(String sessionId, String userId, String canonicalRole)
            throws CheckpointResolutionException {
        int cascaded = new DelegateApprovalCascade(agentStore, checkpointApprovalService, auditService)
                .denyAwaitingDelegates(sessionId, userId, canonicalRole);
        if (cascaded > 0) {
            auditService.recordMandatory(
                    sessionId, userId, canonicalRole, SecurityEventType.APPROVAL_DENIED, "via=REST delegated-child");
            return true;
        }
        return false;
    }

    private ServiceResult<Object> resume(
            String sessionId, String userId, String agentRole, String canonicalRole, AgentCheckpoint checkpoint)
            throws Exception {
        String resumeGoal = checkpointResumeHandler.resolveResumeGoal(sessionId, canonicalRole);
        int resumeIterations = checkpoint.getMaxIterations() != null
                ? Math.max(maxResumeIterations, checkpoint.getMaxIterations())
                : maxResumeIterations;
        MessageContext context = createRestContext(sessionId, userId, resumeGoal);
        context.setResumeSignal(true);
        AgentResult result =
                agentLoop.run(sessionId, agentRole, resumeGoal, reasoningEngine, resumeIterations, context);
        return new ServiceResult<>(responseMapper.mapResult(result, false), null, null);
    }

    private MessageContext createRestContext(String sessionId, String userId, String goal) {
        ChannelUser user = new ChannelUser(userId, userId, REST_USER_NAME, ChannelType.REST_API);
        ChannelMessage msg =
                new ChannelMessage.RestApiMessage(user, goal, REST_API_SESSION_PREFIX + sessionId, Instant.now());
        return new MessageContext(REST_CHAT_SESSION_PREFIX + sessionId, user, msg);
    }

    private <T> T withSelectedWorkspace(Callable<T> task) throws Exception {
        String workspaceId = activeWorkspaceContext == null
                ? null
                : activeWorkspaceContext.workspaceId().orElse(null);
        return workspaceId == null ? task.call() : WorkspaceContext.callWithActiveWorkspace(workspaceId, task);
    }

    private static ServiceResult<Object> noPendingApproval() {
        return ServiceResult.error("NO_PENDING_APPROVAL", "No pending approval");
    }

    private static String resolutionErrorCode(CheckpointResolutionException e) {
        return switch (e.getStatusCode()) {
            case 409 -> "ALREADY_RESOLVED";
            case 404 -> "NO_PENDING_APPROVAL";
            default -> "SYSTEM";
        };
    }

    private static boolean isAlreadyResolved(CheckpointResolutionException e) {
        return e.getStatusCode() == 409
                && e.getMessage() != null
                && e.getMessage().startsWith("Approval decision already resolved");
    }

    private static void logRace(String operation, String sessionId, CheckpointResolutionException exception) {
        Logger.getLogger(AgentSessionApprovalHandler.class)
                .warnf(
                        "%s raced with an already-resolved checkpoint for session %s: %s",
                        operation, sessionId, LogUtil.rootMessage(exception));
    }
}
