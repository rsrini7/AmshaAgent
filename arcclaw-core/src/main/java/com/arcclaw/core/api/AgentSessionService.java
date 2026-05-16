package com.arcclaw.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.api.dto.ServiceResult;
import com.arcclaw.core.api.dto.SessionStatusResponse;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.util.LogUtil;
import com.arcclaw.core.util.MdcManaged;
import io.quarkus.virtual.threads.VirtualThreads;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
@MdcManaged
public class AgentSessionService {

    private final Logger log;
    private final AgentLoop agentLoop;
    private final ReasoningEngine reasoningEngine;
    private final StatefulAgentStore agentStore;
    private final WorkspaceManager workspaceManager;
    private final SessionHeartbeatTracker heartbeatTracker;
    private final AgentResponseMapper responseMapper;
    private final ReflexRegistry reflexRegistry;
    private final CheckpointResumeHandler checkpointResumeHandler;
    private final CheckpointApprovalService checkpointApprovalService;
    private final AuditService auditService;
    private final ExecutorService vThreads;
    private final int maxResumeIterations;

    private static final List<String> NON_SLASH_REFLEX_PREFIXES = List.of(
            "deletecron"
    );

    private static final String REST_USER_ID = ApiConstants.REST_USER_ID;
    private static final String REST_USER_NAME = ApiConstants.REST_USER_NAME;
    private static final String REST_API_SESSION_PREFIX = "rest-api-";
    private static final String REST_CHAT_SESSION_PREFIX = "rest-chat-";

    protected AgentSessionService() {
        this.log = null;
        this.agentLoop = null;
        this.reasoningEngine = null;
        this.agentStore = null;
        this.workspaceManager = null;
        this.heartbeatTracker = null;
        this.responseMapper = null;
        this.reflexRegistry = null;
        this.checkpointResumeHandler = null;
        this.checkpointApprovalService = null;
        this.auditService = null;
        this.vThreads = null;
        this.maxResumeIterations = 0;
    }

    @Inject
    public AgentSessionService(Logger log,
                               AgentLoop agentLoop,
                               ReasoningEngine reasoningEngine,
                               StatefulAgentStore agentStore,
                               WorkspaceManager workspaceManager,
                               SessionHeartbeatTracker heartbeatTracker,
                               AgentResponseMapper responseMapper,
                               ReflexRegistry reflexRegistry,
                               CheckpointResumeHandler checkpointResumeHandler,
                               CheckpointApprovalService checkpointApprovalService,
                               AuditService auditService,
                               @VirtualThreads ExecutorService vThreads,
                               @ConfigProperty(name = "arcclaw.approval.max-resume-iterations", defaultValue = "5") int maxResumeIterations) {
        this.log = log;
        this.agentLoop = agentLoop;
        this.reasoningEngine = reasoningEngine;
        this.agentStore = agentStore;
        this.workspaceManager = workspaceManager;
        this.heartbeatTracker = heartbeatTracker;
        this.responseMapper = responseMapper;
        this.reflexRegistry = reflexRegistry;
        this.checkpointResumeHandler = checkpointResumeHandler;
        this.checkpointApprovalService = checkpointApprovalService;
        this.auditService = auditService;
        this.vThreads = vThreads;
        this.maxResumeIterations = maxResumeIterations;
    }

    public String createSession(String userId, AutonomyLevel autonomyLevel) {
        String sessionId = UUID.randomUUID().toString();
        AutonomyLevel level = (autonomyLevel != null) ? autonomyLevel : AutonomyLevel.SUPERVISED;
        log.infof("Creating new session: %s (autonomy=%s)", sessionId, level);
        heartbeatTracker.recordHeartbeat(sessionId, level);

        if (userId != null && !userId.isBlank() && !"unknown".equals(userId)) {
            heartbeatTracker.tryBindOwner(sessionId, userId);
        }
        return sessionId;
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

        var commandResponse = handleSlashCommand(sessionId, request);
        if (commandResponse.isPresent()) return commandResponse.get();

        String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();
        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        Optional<AgentCheckpoint> cpOpt = agentStore.load(sessionId, canonicalRole);
        if (cpOpt.isPresent() && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
            return ServiceResult.error("AWAITING_APPROVAL",
                    "Session is awaiting human approval");
        }

        try {
            Callable<ServiceResult<Object>> op = () -> {
                AutonomyLevel level = getOrDefaultAutonomy(sessionId);
                String channel = ChannelType.REST_API.name();

                return WorkspaceContext.<ServiceResult<Object>>run(sessionId, REST_USER_ID, sessionId, level, canonicalRole, channel, () -> {
                    MessageContext context = createRestContext(sessionId, request.goal());
                    context.setAutonomyLevel(level);
                    AgentResult result = agentLoop.run(sessionId, canonicalRole, request.goal(), reasoningEngine, maxIterations, context);
                    return new ServiceResult<>(responseMapper.mapResult(result, false), null, null);
                });
            };
            return WorkspaceScopeHolder.call(sessionId, op);
        } catch (BudgetExceededException e) {
            return ServiceResult.error("BUDGET", e.getMessage());
        } catch (Exception e) {
            log.errorf("Sync agent run failed: %s", LogUtil.rootMessage(e));
            return ServiceResult.error("SYSTEM", e.getMessage() != null ? e.getMessage() : "Internal error");
        }
    }

    public void runAsync(String sessionId, String userId, RunAgentRequest request, Consumer<ServiceResult<Object>> callback) {
        var sessionError = validateSession(sessionId, userId);
        if (sessionError.isPresent()) { callback.accept(sessionError.get()); return; }

        var roleError = validateRole(request.agentRole());
        if (roleError.isPresent()) { callback.accept(roleError.get()); return; }

        vThreads.execute(() -> WorkspaceScopeHolder.run(sessionId, () -> {
            try {
                var commandResponse = handleSlashCommand(sessionId, request);
                if (commandResponse.isPresent()) {
                    callback.accept(commandResponse.get());
                    return;
                }

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

                AutonomyLevel sessionLevel = getOrDefaultAutonomy(sessionId);
                String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();
                String channel = ChannelType.REST_API.name();
                int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

                WorkspaceContext.<Void>run(sessionId, REST_USER_ID, sessionId, sessionLevel, canonicalRole, channel, () -> {
                    MessageContext context = createRestContext(sessionId, request.goal());
                    context.setAutonomyLevel(sessionLevel);
                    AgentResult result = agentLoop.run(sessionId, canonicalRole, request.goal(), reasoningEngine, maxIterations, context);
                    callback.accept(new ServiceResult<>(responseMapper.mapResult(result, true), null, null));
                    return null;
                });
            } catch (Exception e) {
                log.errorf("Async agent run failed: %s", LogUtil.rootMessage(e));
                callback.accept(ServiceResult.error("SYSTEM", e.getMessage() != null ? e.getMessage() : "Internal error"));
            }
        }));
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
                String channel = ChannelType.REST_API.name();

                return WorkspaceContext.<ServiceResult<Object>>run(sessionId, REST_USER_ID, sessionId, AutonomyLevel.FULL, canonicalRole, channel, () -> {
                    String resumeGoal = checkpointResumeHandler.resolveResumeGoal(sessionId, canonicalRole);
                    int resumeIterations = checkpoint.get().getMaxIterations() != null ? Math.min(checkpoint.get().getMaxIterations(), maxResumeIterations) : maxResumeIterations;
                    MessageContext context = createRestContext(sessionId, resumeGoal);
                    context.setResumeSignal(true);
                    AgentResult result = agentLoop.run(sessionId, agentRole, resumeGoal, reasoningEngine, resumeIterations, context);
                    return new ServiceResult<>(responseMapper.mapResult(result, false), null, null);
                });
            };
            return WorkspaceScopeHolder.call(sessionId, op);
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
                checkpointApprovalService.triggerResume(cp);
                return ServiceResult.success(Map.of("status", ToolDecision.DENIED.name(), "session", sessionId));
            };
            return WorkspaceScopeHolder.call(sessionId, op);
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
                return ServiceResult.success(Map.of("sessionId", sessionId, "status", CheckpointStatus.NOT_STARTED.name()));
            }
            var cp = checkpointOpt.get();
            List<ReasoningStep> steps = agentStore.loadSteps(cp.getId());
            return ServiceResult.success(new SessionStatusResponse(sessionId, cp.getStatus().name(), steps, cp.getFailReason()));
        } catch (Exception e) {
            return ServiceResult.error("SYSTEM", e.getMessage());
        }
    }

    public ServiceResult<Object> terminateSession(String sessionId, String userId) {
        var sessionError = validateSession(sessionId, userId);
        if (sessionError.isPresent()) return sessionError.get();

        workspaceManager.terminateSession(sessionId);
        return ServiceResult.success(null);
    }

    private MessageContext createRestContext(String sessionId, String goal) {
        ChannelUser user = new ChannelUser(REST_USER_ID, REST_USER_ID, REST_USER_NAME, ChannelType.REST_API);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, goal, REST_API_SESSION_PREFIX + sessionId, Instant.now());
        return new MessageContext(REST_CHAT_SESSION_PREFIX + sessionId, user, msg);
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
                log.warnf("[SESSION-ISOLATION] key=%s attempted access to session=%s owned by key=%s",
                          userId, sessionId, owner.get());

                auditService.recordMandatory(
                    sessionId,
                    userId,
                    AgentSentinels.SYSTEM_ROLE,
                    SecurityEventType.UNAUTHORIZED_ACCESS,
                    "Key " + userId + " attempted cross-key session access"
                );

                return Optional.of(ServiceResult.error("SESSION_ACCESS_DENIED",
                        "API key not authorized for this session"));
            }
        }
        return Optional.empty();
    }

    private Optional<ServiceResult<Object>> handleSlashCommand(String sessionId, RunAgentRequest request) {
        String goal = request.goal();
        if (goal == null || (!goal.startsWith("/") && NON_SLASH_REFLEX_PREFIXES.stream().noneMatch(goal.toLowerCase()::startsWith))) return Optional.empty();

        try {
            Callable<ServiceResult<Object>> op = () -> {
                String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();
                String channel = ChannelType.REST_API.name();
                return WorkspaceContext.<ServiceResult<Object>>run(sessionId, REST_USER_ID, sessionId, AutonomyLevel.FULL, canonicalRole, channel, () -> {
                    MessageContext context = createRestContext(sessionId, goal);
                    ReflexResult result = reflexRegistry.processIfReflex(sessionId, REST_USER_ID, sessionId, goal, context);
                    return result.commandResponse()
                            .map(r -> ServiceResult.success((Object) Map.of("status", "OK", "message", r)))
                            .orElse(null);
                });
            };
            return Optional.ofNullable(WorkspaceScopeHolder.call(sessionId, op));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private AutonomyLevel getOrDefaultAutonomy(String sessionId) {
        AutonomyLevel level = heartbeatTracker.getAutonomyLevel(sessionId);
        return (level != null) ? level : AutonomyLevel.SUPERVISED;
    }
}
