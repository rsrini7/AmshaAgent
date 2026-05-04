package com.arcclaw.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

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
import com.arcclaw.core.api.dto.SessionStatusResponse;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
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

    private static final String REST_USER_ID = "rest:api";
    private static final String REST_USER_NAME = "REST API User";
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

    @Context
    SecurityContext securityContext;

    public String createSession(AutonomyLevel autonomyLevel) {
        String sessionId = UUID.randomUUID().toString();
        AutonomyLevel level = (autonomyLevel != null) ? autonomyLevel : AutonomyLevel.SUPERVISED;
        log.infof("Creating new session: %s (autonomy=%s)", sessionId, level);
        heartbeatTracker.recordHeartbeat(sessionId, level);

        String requestingKey = resolveUserId();
        if (requestingKey != null && !requestingKey.isBlank() && !"unknown".equals(requestingKey)) {
            heartbeatTracker.tryBindOwner(sessionId, requestingKey);
        }
        return sessionId;
    }

    public Map<String, Instant> listSessions() {
        String requestingKey = resolveUserId();
        if (requestingKey == null || requestingKey.isBlank()) {
            return Map.of();
        }
        return heartbeatTracker.activeSessionsByOwner(requestingKey);
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    public Response runSync(String sessionId, RunAgentRequest request) {
        var sessionError = validateSession(sessionId);
        if (sessionError.isPresent()) return sessionError.get();

        var roleError = validateRole(request.agentRole());
        if (roleError.isPresent()) return roleError.get();

        if (request.goal() != null && request.goal().startsWith("CRON:")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Reserved goal prefix", "code", "FORK_BOMB_REJECTED"))
                    .build();
        }

        var commandResponse = handleSlashCommand(sessionId, request);
        if (commandResponse.isPresent()) return commandResponse.get();

        String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();
        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        Optional<AgentCheckpoint> cpOpt = agentStore.load(sessionId, canonicalRole);
        if (cpOpt.isPresent() && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(
                        "error",  "Session is awaiting human approval",
                        "code",   "AWAITING_APPROVAL",
                        "hint",   "Use POST /sessions/" + sessionId + "/approve to proceed"
                    ))
                    .build();
        }

        try {
            Callable<Response> op = () -> {
                AutonomyLevel level = getOrDefaultAutonomy(sessionId);
                String channel = ChannelType.REST_API.name();
                
                return WorkspaceContext.<Response>run(sessionId, REST_USER_ID, sessionId, level, canonicalRole, channel, () -> {
                    MessageContext context = createRestContext(sessionId, request.goal());
                    context.setAutonomyLevel(level);
                    AgentResult result = agentLoop.run(sessionId, canonicalRole, request.goal(), reasoningEngine, maxIterations, context);
                    return responseMapper.mapResult(result, false);
                });
            };
            return WorkspaceScopeHolder.call(sessionId, op);
        } catch (BudgetExceededException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage(), "code", "BUDGET")).build();
        } catch (Exception e) {
            log.errorf("Sync agent run failed: %s", e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error")).build();
        }
    }

    public void runAsync(String sessionId, RunAgentRequest request, AsyncResponse response) {
        var sessionError = validateSession(sessionId);
        if (sessionError.isPresent()) { response.resume(sessionError.get()); return; }

        var roleError = validateRole(request.agentRole());
        if (roleError.isPresent()) { response.resume(roleError.get()); return; }

        vThreads.execute(() -> WorkspaceScopeHolder.run(sessionId, () -> {
            try {
                var commandResponse = handleSlashCommand(sessionId, request);
                if (commandResponse.isPresent()) {
                    response.resume(commandResponse.get());
                    return;
                }

                String goal = request.goal() != null ? request.goal().trim() : "";
                if ("approve".equalsIgnoreCase(goal) || "/approve".equalsIgnoreCase(goal)) {
                    AgentCheckpoint cp = checkpointApprovalService.approveAll(sessionId, "Human approved via REST");
                    checkpointApprovalService.triggerResume(cp);
                    response.resume(Response.accepted(Map.of("status", "APPROVED")).build());
                    return;
                }
                if ("deny".equalsIgnoreCase(goal) || "/deny".equalsIgnoreCase(goal)) {
                    AgentCheckpoint cp = checkpointApprovalService.denyAll(sessionId, "Human denied via REST");
                    checkpointApprovalService.triggerResume(cp);
                    response.resume(Response.ok(Map.of("status", "DENIED")).build());
                    return;
                }

                AutonomyLevel sessionLevel = getOrDefaultAutonomy(sessionId);
                String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();
                String channel = ChannelType.REST_API.name();
                int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

                WorkspaceContext.<Response>run(sessionId, REST_USER_ID, sessionId, sessionLevel, canonicalRole, channel, () -> {
                    MessageContext context = createRestContext(sessionId, request.goal());
                    context.setAutonomyLevel(sessionLevel);
                    AgentResult result = agentLoop.run(sessionId, canonicalRole, request.goal(), reasoningEngine, maxIterations, context);
                    Response res = responseMapper.mapResult(result, true);
                    response.resume(res);
                    return res;
                });
            } catch (Exception e) {
                log.errorf("Async agent run failed: %s", e.getMessage());
                response.resume(Response.serverError().entity(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error")).build());
            }
        }));
    }

    public Response approve(String sessionId, String agentRole) {
        var sessionError = validateSession(sessionId);
        if (sessionError.isPresent()) return sessionError.get();

        try {
            Callable<Response> op = () -> {
                String canonicalRole = AgentRoleEnum.fromString(agentRole).toScopedName();
                var checkpoint = agentStore.load(sessionId, canonicalRole);
                if (checkpoint.isEmpty() || checkpoint.get().getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
                    return Response.status(Response.Status.CONFLICT).entity(Map.of("error", "No pending approval")).build();
                }

                checkpointApprovalService.approveAll(sessionId, "Human approved via REST");
                String channel = ChannelType.REST_API.name();

                return WorkspaceContext.<Response>run(sessionId, REST_USER_ID, sessionId, AutonomyLevel.FULL, canonicalRole, channel, () -> {
                    String resumeGoal = checkpointResumeHandler.resolveResumeGoal(sessionId, canonicalRole);
                    int resumeIterations = checkpoint.get().getMaxIterations() != null ? Math.min(checkpoint.get().getMaxIterations(), maxResumeIterations) : maxResumeIterations;
                    MessageContext context = createRestContext(sessionId, resumeGoal);
                    context.setResumeSignal(true);
                    AgentResult result = agentLoop.run(sessionId, agentRole, resumeGoal, reasoningEngine, resumeIterations, context);
                    return responseMapper.mapResult(result, false);
                });
            };
            return WorkspaceScopeHolder.call(sessionId, op);
        } catch (Exception e) {
            log.errorf("Approve failed: %s", e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error")).build();
        }
    }

    public Response deny(String sessionId, String agentRole) {
        var sessionError = validateSession(sessionId);
        if (sessionError.isPresent()) return sessionError.get();

        try {
            Callable<Response> op = () -> {
                String canonicalRole = AgentRoleEnum.fromString(agentRole).toScopedName();
                var checkpoint = agentStore.load(sessionId, canonicalRole);
                if (checkpoint.isEmpty() || checkpoint.get().getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
                    return Response.status(Response.Status.CONFLICT).entity(Map.of("error", "No pending approval")).build();
                }
                AgentCheckpoint cp = checkpointApprovalService.denyAll(sessionId, "Human denied via REST");
                checkpointApprovalService.triggerResume(cp);
                return Response.ok(Map.of("status", ToolDecision.DENIED.name(), "session", sessionId)).build();
            };
            return WorkspaceScopeHolder.call(sessionId, op);
        } catch (Exception e) {
            log.errorf("Deny failed: %s", e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error")).build();
        }
    }

    public Response getStatus(String sessionId, String role) {
        var sessionError = validateSession(sessionId);
        if (sessionError.isPresent()) return sessionError.get();

        try {
            String canonicalRole = AgentRoleEnum.fromString(role).toScopedName();
            return agentStore.load(sessionId, canonicalRole)
                    .map(cp -> {
                        try {
                            List<ReasoningStep> steps = agentStore.loadSteps(cp.getId());
                            return Response.ok(new SessionStatusResponse(sessionId, cp.getStatus().name(), steps, cp.getFailReason())).build();
                        } catch (Exception e) {
                            log.errorf("Failed to load steps: %s", e.getMessage());
                            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
                        }
                    })
                    .orElseGet(() -> Response.ok(Map.of("sessionId", sessionId, "status", "NOT_STARTED")).build());
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
    }

    public Response terminateSession(String sessionId) {
        var sessionError = validateSession(sessionId);
        if (sessionError.isPresent()) return sessionError.get();

        workspaceManager.terminateSession(sessionId);
        return Response.noContent().build();
    }

    private MessageContext createRestContext(String sessionId, String goal) {
        ChannelUser user = new ChannelUser(REST_USER_ID, REST_USER_ID, REST_USER_NAME, ChannelType.REST_API);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, goal, REST_API_SESSION_PREFIX + sessionId, Instant.now());
        return new MessageContext(REST_CHAT_SESSION_PREFIX + sessionId, user, msg);
    }

    private Optional<Response> validateRole(String role) {
        if (!AgentRoleEnum.isKnownRole(role)) {
            return Optional.of(Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Unknown role: " + role)).build());
        }
        return Optional.empty();
    }

    public Optional<Response> validateSession(String sessionId) {
        if (!heartbeatTracker.exists(sessionId)) {
            return Optional.of(Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Session not found: " + sessionId)).build());
        }

        // Guard 2: per-key session ownership
        Optional<String> owner = heartbeatTracker.getOwner(sessionId);
        if (owner.isPresent()) {
            String requestingKey = resolveUserId();
            if (requestingKey != null && !owner.get().equals(requestingKey)) {
                log.warnf("[SESSION-ISOLATION] key=%s attempted access to session=%s owned by key=%s",
                          requestingKey, sessionId, owner.get());
                
                auditService.recordMandatory(
                    sessionId,
                    requestingKey,
                    AgentSentinels.SYSTEM_ROLE,
                    SecurityEventType.UNAUTHORIZED_ACCESS,
                    "Key " + requestingKey + " attempted cross-key session access"
                );

                return Optional.of(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of(
                        "error", "API key not authorized for this session",
                        "code", "SESSION_ACCESS_DENIED"
                    ))
                    .build());
            }
        }
        return Optional.empty();
    }

    private Optional<Response> handleSlashCommand(String sessionId, RunAgentRequest request) {
        String goal = request.goal();
        if (goal == null || (!goal.startsWith("/") && NON_SLASH_REFLEX_PREFIXES.stream().noneMatch(goal.toLowerCase()::startsWith))) return Optional.empty();

        try {
            Callable<Response> op = () -> {
                String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();
                String channel = ChannelType.REST_API.name();
                return WorkspaceContext.<Response>run(sessionId, REST_USER_ID, sessionId, AutonomyLevel.FULL, canonicalRole, channel, () -> {
                    MessageContext context = createRestContext(sessionId, goal);
                    ReflexResult result = reflexRegistry.processIfReflex(sessionId, REST_USER_ID, sessionId, goal, context);
                    return result.commandResponse().map(r -> Response.ok(Map.of("status", "OK", "message", r)).build()).orElse(null);
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
