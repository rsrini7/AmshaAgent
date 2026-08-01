package com.amsha.core.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.api.dto.RunAgentRequest;
import com.amsha.core.api.dto.ServiceResult;
import com.amsha.core.api.dto.SessionStatusResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.lifecycle.SessionTestLabelStore;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.TurnDispatchResult;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.pipeline.TurnRequest;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.session.SessionCatalogService;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.state.ToolDecision;
import com.amsha.core.util.MdcManaged;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.virtual.threads.VirtualThreads;

@ApplicationScoped
@MdcManaged
@SuppressWarnings("java:S1192")
public class AgentSessionService {

    private final Logger log;
    private final AgentLoop agentLoop;
    private final TurnDispatchService turnDispatchService;
    private final ReasoningEngine reasoningEngine;
    private final StatefulAgentStore agentStore;
    private final WorkspaceManager workspaceManager;
    private final SessionHeartbeatTracker heartbeatTracker;
    private final AgentResponseMapper responseMapper;
    private final CheckpointResumeHandler checkpointResumeHandler;
    private final CheckpointApprovalService checkpointApprovalService;
    private final AuditService auditService;
    private final SessionCatalogService sessionCatalogService;
    private final ExecutorService vThreads;
    private final ActiveWorkspaceContext activeWorkspaceContext;
    private final SessionTestLabelStore sessionTestLabelStore;
    private final int maxResumeIterations;

    private static final String REST_USER_NAME = ApiConstants.REST_USER_NAME;
    private static final String REST_API_SESSION_PREFIX = "rest-api-";
    private static final String REST_CHAT_SESSION_PREFIX = "rest-chat-";

    protected AgentSessionService() {
        this.log = null;
        this.agentLoop = null;
        this.turnDispatchService = null;
        this.reasoningEngine = null;
        this.agentStore = null;
        this.workspaceManager = null;
        this.heartbeatTracker = null;
        this.responseMapper = null;
        this.checkpointResumeHandler = null;
        this.checkpointApprovalService = null;
        this.auditService = null;
        this.sessionCatalogService = null;
        this.vThreads = null;
        this.activeWorkspaceContext = null;
        this.sessionTestLabelStore = new SessionTestLabelStore();
        this.maxResumeIterations = 0;
    }

    @Inject
    public AgentSessionService(
            Logger log,
            AgentLoop agentLoop,
            TurnDispatchService turnDispatchService,
            ReasoningEngine reasoningEngine,
            StatefulAgentStore agentStore,
            WorkspaceManager workspaceManager,
            SessionHeartbeatTracker heartbeatTracker,
            AgentResponseMapper responseMapper,
            CheckpointResumeHandler checkpointResumeHandler,
            CheckpointApprovalService checkpointApprovalService,
            AuditService auditService,
            SessionCatalogService sessionCatalogService,
            ActiveWorkspaceContext activeWorkspaceContext,
            SessionTestLabelStore sessionTestLabelStore,
            @VirtualThreads ExecutorService vThreads,
            @ConfigProperty(name = "amsha.approval.max-resume-iterations", defaultValue = "5")
                    int maxResumeIterations) {
        this.log = log;
        this.agentLoop = agentLoop;
        this.turnDispatchService = turnDispatchService;
        this.reasoningEngine = reasoningEngine;
        this.agentStore = agentStore;
        this.workspaceManager = workspaceManager;
        this.heartbeatTracker = heartbeatTracker;
        this.responseMapper = responseMapper;
        this.checkpointResumeHandler = checkpointResumeHandler;
        this.checkpointApprovalService = checkpointApprovalService;
        this.auditService = auditService;
        this.sessionCatalogService = sessionCatalogService;
        this.activeWorkspaceContext = activeWorkspaceContext;
        this.sessionTestLabelStore =
                sessionTestLabelStore != null ? sessionTestLabelStore : new SessionTestLabelStore();
        this.vThreads = vThreads;
        this.maxResumeIterations = maxResumeIterations;
    }

    /** Compatibility constructor retained for focused unit tests. */
    public AgentSessionService(
            Logger log,
            AgentLoop agentLoop,
            TurnDispatchService turnDispatchService,
            ReasoningEngine reasoningEngine,
            StatefulAgentStore agentStore,
            WorkspaceManager workspaceManager,
            SessionHeartbeatTracker heartbeatTracker,
            AgentResponseMapper responseMapper,
            CheckpointResumeHandler checkpointResumeHandler,
            CheckpointApprovalService checkpointApprovalService,
            AuditService auditService,
            @VirtualThreads ExecutorService vThreads,
            int maxResumeIterations) {
        this(
                log,
                agentLoop,
                turnDispatchService,
                reasoningEngine,
                agentStore,
                workspaceManager,
                heartbeatTracker,
                responseMapper,
                checkpointResumeHandler,
                checkpointApprovalService,
                auditService,
                null,
                null,
                new SessionTestLabelStore(),
                vThreads,
                maxResumeIterations);
    }

    public String createSession(String userId, AutonomyLevel autonomyLevel) {
        return createSession(userId, autonomyLevel, Map.of(), ChannelType.REST_API.id(), "rest-chat");
    }

    public String createSession(String userId, AutonomyLevel autonomyLevel, Map<String, String> expectedLabels) {
        return createSession(userId, autonomyLevel, expectedLabels, ChannelType.REST_API.id(), "rest-chat");
    }

    public String createSession(String userId, AutonomyLevel autonomyLevel, String channelType, String channelId) {
        return createSession(userId, autonomyLevel, Map.of(), channelType, channelId);
    }

    public String createSession(
            String userId,
            AutonomyLevel autonomyLevel,
            Map<String, String> expectedLabels,
            String channelType,
            String channelId) {
        if (autonomyLevel == AutonomyLevel.FULL
                && expectedLabels != null
                && "REQUIRE_APPROVAL".equals(expectedLabels.get("tool_risk"))) {
            throw new IllegalArgumentException("FULL autonomy cannot produce REQUIRE_APPROVAL observations");
        }

        String sessionId = sessionCatalogService != null
                ? sessionCatalogService.createSession(userId, channelType, channelId)
                : UUID.randomUUID().toString();
        AutonomyLevel level = (autonomyLevel != null) ? autonomyLevel : AutonomyLevel.SUPERVISED;
        log.infof("Creating new session: %s (autonomy=%s)", sessionId, level);
        heartbeatTracker.recordHeartbeat(sessionId, level);

        if (userId != null && !userId.isBlank() && !"unknown".equals(userId)) {
            heartbeatTracker.tryBindOwner(sessionId, userId);
        }

        auditService.recordMandatory(
                sessionId,
                userId != null ? userId : "unknown",
                "system",
                SecurityEventType.SESSION_CREATED,
                "autonomy=" + level.name());

        if (expectedLabels != null && !expectedLabels.isEmpty()) {
            sessionTestLabelStore.put(sessionId, expectedLabels);
        }

        return sessionId;
    }

    public String getExpectedTestLabel(String sessionId, String task) {
        return sessionTestLabelStore.get(sessionId, task);
    }

    public Map<String, Instant> listSessions(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        return heartbeatTracker.activeSessionsByOwner(userId);
    }

    public ServiceResult<Object> runSync(String sessionId, String userId, RunAgentRequest request) {
        var sessionError = validateSession(sessionId, userId);
        if (sessionError.isPresent()) return sessionError.get();

        var roleError = validateRole(request.agentRole());
        if (roleError.isPresent()) return roleError.get();

        if (request.goal() != null && request.goal().startsWith("CRON:")) {
            return ServiceResult.error("FORK_BOMB_REJECTED", "Reserved goal prefix");
        }

        String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();
        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        Optional<AgentCheckpoint> cpOpt = agentStore.load(sessionId, canonicalRole);
        if (cpOpt.isPresent() && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
            return ServiceResult.error("AWAITING_APPROVAL", "Session is awaiting human approval");
        }

        try {
            TurnDispatchResult dispatched = withSelectedWorkspace(() -> WorkspaceScopeHolder.call(
                    sessionId, () -> dispatchRestTurn(sessionId, userId, request, canonicalRole, maxIterations)));
            ServiceResult<Object> result = toServiceResult(dispatched, false);
            if (!isAwaitingApproval(result)) {
                sessionTestLabelStore.clearSession(sessionId);
            }
            return result;
        } catch (BudgetExceededException e) {
            sessionTestLabelStore.clearSession(sessionId);
            return ServiceResult.error("BUDGET", e.getMessage());
        } catch (Exception e) {
            sessionTestLabelStore.clearSession(sessionId);
            log.errorf("Sync agent run failed: %s", LogUtil.rootMessage(e));
            return ServiceResult.error("SYSTEM", e.getMessage() != null ? e.getMessage() : "Internal error");
        }
    }

    public void runAsync(
            String sessionId, String userId, RunAgentRequest request, Consumer<ServiceResult<Object>> callback) {
        var sessionError = validateSession(sessionId, userId);
        if (sessionError.isPresent()) {
            callback.accept(sessionError.get());
            return;
        }

        var roleError = validateRole(request.agentRole());
        if (roleError.isPresent()) {
            callback.accept(roleError.get());
            return;
        }

        String activeWorkspaceId = selectedWorkspaceId();
        Runnable dispatch = () -> WorkspaceScopeHolder.run(sessionId, () -> {
            try {
                String goal = request.goal() != null ? request.goal().trim() : "";
                if ("approve".equalsIgnoreCase(goal) || "/approve".equalsIgnoreCase(goal)) {
                    AgentCheckpoint cp = checkpointApprovalService.approveAll(sessionId, "Human approved via REST");
                    checkpointApprovalService.triggerResume(cp);
                    callback.accept(ServiceResult.success(Map.of("status", CheckpointStatus.APPROVED.name())));
                    return;
                }
                if ("deny".equalsIgnoreCase(goal) || "/deny".equalsIgnoreCase(goal)) {
                    AgentCheckpoint cp = checkpointApprovalService.denyAll(sessionId, "Human denied via REST");
                    checkpointApprovalService.triggerResume(cp);
                    callback.accept(ServiceResult.success(Map.of("status", CheckpointStatus.DENIED.name())));
                    return;
                }

                String canonicalRole =
                        AgentRoleEnum.fromString(request.agentRole()).toScopedName();
                int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;
                ServiceResult<Object> result = toServiceResult(
                        dispatchRestTurn(sessionId, userId, request, canonicalRole, maxIterations), true);
                if (!isAwaitingApproval(result)) {
                    sessionTestLabelStore.clearSession(sessionId);
                }
                callback.accept(result);
            } catch (Exception e) {
                sessionTestLabelStore.clearSession(sessionId);
                log.errorf("Async agent run failed: %s", LogUtil.rootMessage(e));
                callback.accept(
                        ServiceResult.error("SYSTEM", e.getMessage() != null ? e.getMessage() : "Internal error"));
            }
        });
        if (activeWorkspaceId == null) {
            vThreads.execute(dispatch);
        } else {
            vThreads.execute(() -> WorkspaceContext.runWithActiveWorkspace(activeWorkspaceId, dispatch));
        }
    }

    public ServiceResult<Object> approve(String sessionId, String userId, String agentRole) {
        var sessionError = validateSession(sessionId, userId);
        if (sessionError.isPresent()) return sessionError.get();

        try {
            Callable<ServiceResult<Object>> op = () -> {
                String canonicalRole = AgentRoleEnum.fromString(agentRole).toScopedName();
                var checkpoint = agentStore.load(sessionId, canonicalRole);
                if (checkpoint.isEmpty() || checkpoint.get().getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
                    return ServiceResult.error("NO_PENDING_APPROVAL", "No pending approval");
                }

                checkpointApprovalService.approveAll(sessionId, "Human approved via REST");
                auditService.recordMandatory(
                        sessionId,
                        userId,
                        canonicalRole,
                        SecurityEventType.APPROVAL_GRANTED,
                        "via=REST role=" + agentRole);
                String channel = ChannelType.REST_API.name();

                return WorkspaceContext.<ServiceResult<Object>>run(
                        sessionId, userId, sessionId, AutonomyLevel.FULL, canonicalRole, channel, () -> {
                            String resumeGoal = checkpointResumeHandler.resolveResumeGoal(sessionId, canonicalRole);
                            int resumeIterations = checkpoint.get().getMaxIterations() != null
                                    ? Math.max(
                                            maxResumeIterations,
                                            checkpoint.get().getMaxIterations())
                                    : maxResumeIterations;
                            MessageContext context = createRestContext(sessionId, userId, resumeGoal);
                            context.setResumeSignal(true);
                            AgentResult result = agentLoop.run(
                                    sessionId, agentRole, resumeGoal, reasoningEngine, resumeIterations, context);
                            return new ServiceResult<>(responseMapper.mapResult(result, false), null, null);
                        });
            };
            return withSelectedWorkspace(() -> WorkspaceScopeHolder.call(sessionId, op));
        } catch (Exception e) {
            log.errorf("Approve failed: %s", LogUtil.rootMessage(e));
            return ServiceResult.error("SYSTEM", e.getMessage() != null ? e.getMessage() : "Internal error");
        }
    }

    public ServiceResult<Object> deny(String sessionId, String userId, String agentRole) {
        var sessionError = validateSession(sessionId, userId);
        if (sessionError.isPresent()) return sessionError.get();

        try {
            Callable<ServiceResult<Object>> op = () -> {
                String canonicalRole = AgentRoleEnum.fromString(agentRole).toScopedName();
                var checkpoint = agentStore.load(sessionId, canonicalRole);
                if (checkpoint.isEmpty() || checkpoint.get().getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
                    return ServiceResult.error("NO_PENDING_APPROVAL", "No pending approval");
                }
                AgentCheckpoint cp = checkpointApprovalService.denyAll(sessionId, "Human denied via REST");
                auditService.recordMandatory(
                        sessionId,
                        userId,
                        canonicalRole,
                        SecurityEventType.APPROVAL_DENIED,
                        "via=REST role=" + agentRole);
                checkpointApprovalService.triggerResume(cp);
                return ServiceResult.success(Map.of("status", ToolDecision.DENIED.name(), "session", sessionId));
            };
            return withSelectedWorkspace(() -> WorkspaceScopeHolder.call(sessionId, op));
        } catch (Exception e) {
            log.errorf("Deny failed: %s", LogUtil.rootMessage(e));
            return ServiceResult.error("SYSTEM", e.getMessage() != null ? e.getMessage() : "Internal error");
        }
    }

    public ServiceResult<Object> getStatus(String sessionId, String userId, String role) {
        var sessionError = validateSession(sessionId, userId);
        if (sessionError.isPresent()) return sessionError.get();

        try {
            String canonicalRole = AgentRoleEnum.fromString(role).toScopedName();
            var checkpointOpt = agentStore.load(sessionId, canonicalRole);
            if (checkpointOpt.isEmpty()) {
                return ServiceResult.success(
                        Map.of("sessionId", sessionId, "status", CheckpointStatus.NOT_STARTED.name()));
            }
            var cp = checkpointOpt.get();
            List<ReasoningStep> steps = agentStore.loadSteps(cp.getId());
            return ServiceResult.success(
                    new SessionStatusResponse(sessionId, cp.getStatus().name(), steps, cp.getFailReason()));
        } catch (Exception e) {
            return ServiceResult.error("SYSTEM", e.getMessage());
        }
    }

    public ServiceResult<Object> terminateSession(String sessionId, String userId) {
        var sessionError = validateSession(sessionId, userId);
        if (sessionError.isPresent()) return sessionError.get();

        auditService.recordMandatory(sessionId, userId, "system", SecurityEventType.SESSION_TERMINATED, "via=REST");
        sessionTestLabelStore.clearSession(sessionId);
        workspaceManager.terminateSession(sessionId);
        return ServiceResult.success(null);
    }

    private MessageContext createRestContext(String sessionId, String userId, String goal) {
        ChannelUser user = new ChannelUser(userId, userId, REST_USER_NAME, ChannelType.REST_API);
        ChannelMessage msg =
                new ChannelMessage.RestApiMessage(user, goal, REST_API_SESSION_PREFIX + sessionId, Instant.now());
        return new MessageContext(REST_CHAT_SESSION_PREFIX + sessionId, user, msg);
    }

    private String selectedWorkspaceId() {
        return activeWorkspaceContext == null
                ? null
                : activeWorkspaceContext.workspaceId().orElse(null);
    }

    private <T> T withSelectedWorkspace(Callable<T> task) throws Exception {
        String workspaceId = selectedWorkspaceId();
        return workspaceId == null ? task.call() : WorkspaceContext.callWithActiveWorkspace(workspaceId, task);
    }

    private TurnDispatchResult dispatchRestTurn(
            String sessionId, String userId, RunAgentRequest request, String canonicalRole, int maxIterations) {
        MessageContext context = createRestContext(sessionId, userId, request.goal());
        return turnDispatchService.dispatch(new TurnRequest(
                context.getMessage(),
                context.getChatId(),
                Optional.of(sessionId),
                Optional.of(canonicalRole),
                Optional.of(maxIterations)));
    }

    private ServiceResult<Object> toServiceResult(TurnDispatchResult dispatched, boolean asynchronous) {
        Optional<String> commandResponse = dispatched.commandResponse();
        if (commandResponse.isPresent()) {
            return ServiceResult.success(Map.of("status", "OK", "message", commandResponse.get()));
        }
        return dispatched
                .agentResult()
                .<ServiceResult<Object>>map(
                        result -> new ServiceResult<>(responseMapper.mapResult(result, asynchronous), null, null))
                .orElseGet(() -> ServiceResult.success(Map.of("status", "OK", "message", "Turn processed")));
    }

    private static boolean isAwaitingApproval(ServiceResult<Object> result) {
        if (result == null) return false;
        if ("AWAITING_APPROVAL".equals(result.errorCode())) return true;
        Object data = result.value();
        if (data instanceof Map<?, ?> map) {
            Object status = map.get("status");
            return status != null && "AWAITING_APPROVAL".equals(status.toString());
        }
        return data != null && data.toString().contains("AWAITING_APPROVAL");
    }

    private Optional<ServiceResult<Object>> validateRole(String role) {
        if (!AgentRoleEnum.isKnownRole(role)) {
            return Optional.of(ServiceResult.error("UNKNOWN_ROLE", "Unknown role: " + role));
        }
        return Optional.empty();
    }

    public Optional<ServiceResult<Object>> validateSession(String sessionId, String userId) {
        if (!heartbeatTracker.exists(sessionId)) {
            return Optional.of(ServiceResult.error("SESSION_NOT_FOUND", "Session not found: " + sessionId));
        }

        Optional<String> owner = heartbeatTracker.getOwner(sessionId);
        if (owner.isPresent()) {
            if (userId != null && !owner.get().equals(userId)) {
                log.warnf(
                        "[SESSION-ISOLATION] key=%s attempted access to session=%s owned by key=%s",
                        userId, sessionId, owner.get());

                auditService.recordMandatory(
                        sessionId,
                        userId,
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.UNAUTHORIZED_ACCESS,
                        "Key " + userId + " attempted cross-key session access");

                return Optional.of(
                        ServiceResult.error("SESSION_ACCESS_DENIED", "API key not authorized for this session"));
            }
        }
        return Optional.empty();
    }
}
