package com.arcclaw.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.api.dto.SessionStatusResponse;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
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
import com.arcclaw.core.util.MdcKeys;
import io.quarkus.virtual.threads.VirtualThreads;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

@ApplicationScoped
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
    private final ExecutorService vThreads;

    /**
     * Non-slash reflex prefixes that must bypass the agent loop and route
     * directly to the reflex pipeline. Add new prefixes here as reflexes
     * are introduced — keeps the routing logic in one place.
     */
    private static final List<String> NON_SLASH_REFLEX_PREFIXES = List.of(
            "deletecron"
    );

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
        this.vThreads = null;
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
                               @VirtualThreads ExecutorService vThreads) {
        this.log = log;
        this.agentLoop = agentLoop;
        this.reasoningEngine = reasoningEngine;
        this.agentStore = agentStore;
        this.workspaceManager = workspaceManager;
        this.heartbeatTracker = heartbeatTracker;
        this.responseMapper = responseMapper;
        this.reflexRegistry = reflexRegistry;
        this.checkpointResumeHandler = checkpointResumeHandler;
        this.vThreads = vThreads;
    }

    public String createSession(AutonomyLevel autonomyLevel) {
        String sessionId = UUID.randomUUID().toString();
        log.infof("Creating new session: %s (autonomy=%s)", sessionId, autonomyLevel);
        heartbeatTracker.recordHeartbeat(sessionId, autonomyLevel != null ? autonomyLevel : AutonomyLevel.SUPERVISED);
        return sessionId;
    }

    public Map<String, Instant> listSessions() {
        return heartbeatTracker.activeSessions();
    }

    public Response runSync(String sessionId, RunAgentRequest request) {
        log.infof("Running agent sync for session:  %s, role:  %s, goal:  %s", sessionId, request.agentRole(),
                request.goal());

        var sessionError = validateSession(sessionId);
        if (sessionError.isPresent()) return sessionError.get();

        var roleError = validateRole(request.agentRole());
        if (roleError.isPresent()) return roleError.get();

        // Fork-bomb guard: CRON: prefix is reserved for AgentCronScheduler internally.
        // Legitimate cron sessions have sessionId starting with "cron-", not a REST UUID.
        if (request.goal() != null && request.goal().startsWith("CRON:")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                        "error", "Goals prefixed with 'CRON:' are reserved for the internal scheduler.",
                        "code",  "FORK_BOMB_REJECTED"
                    ))
                    .build();
        }

        var commandResponse = handleSlashCommand(sessionId, request);
        if (commandResponse.isPresent()) return commandResponse.get();

        String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();

        try {
            agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                if (cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                    throw new AwaitingApprovalException(sessionId);
                }
            });
        } catch (AwaitingApprovalException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(
                        "error",  "Session is awaiting human approval",
                        "code",   "AWAITING_APPROVAL",
                        "hint",   "Use POST /sessions/" + sessionId + "/approve to proceed"
                    ))
                    .build();
        }

        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        try {
            return WorkspaceScopeHolder.call(sessionId, () -> {
                AutonomyLevel level = getOrDefaultAutonomy(sessionId);

                return WorkspaceContext.run(
                        sessionId, "rest:api", sessionId, level, canonicalRole, ChannelType.REST_API, () -> {
                            MessageContext context = createRestContext(sessionId, request.goal());
                            context.setAutonomyLevel(level);
                            AgentResult result = agentLoop.run(sessionId, canonicalRole, request.goal(), reasoningEngine, maxIterations, context);
                            return responseMapper.mapResult(result, false);
                        }
                );
            });
        } catch (BudgetExceededException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage(), "code", "BUDGET")).build();
        } catch (BudgetWarningException e) {
            return Response.ok().entity(Map.of("warning", e.getMessage())).build();
        } catch (Exception e) {
            log.errorf("Sync agent run threw exception for session %s: %s", sessionId, e.getMessage());
            log.debug("Full sync agent failure stack trace:", e);
            return Response.serverError()
                    .entity(e.getMessage() != null ? e.getMessage() : "Unknown technical fault")
                    .build();
        }
    }

    public void runAsync(String sessionId, RunAgentRequest request, AsyncResponse response) {
        log.infof("Submitting async agent run to @VirtualThreads executor, session:  %s", sessionId);

        var sessionError = validateSession(sessionId);
        if (sessionError.isPresent()) { response.resume(sessionError.get()); return; }

        var roleError = validateRole(request.agentRole());
        if (roleError.isPresent()) { response.resume(roleError.get()); return; }

        // Fork-bomb guard: CRON: prefix is reserved for AgentCronScheduler internally.
        // Legitimate cron sessions have sessionId starting with "cron-", not a REST UUID.
        if (request.goal() != null && request.goal().startsWith("CRON:")) {
            response.resume(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                        "error", "Goals prefixed with 'CRON:' are reserved for the internal scheduler.",
                        "code",  "FORK_BOMB_REJECTED"
                    ))
                    .build());
            return;
        }

        var commandResponse = handleSlashCommand(sessionId, request);
        if (commandResponse.isPresent()) { response.resume(commandResponse.get()); return; }

        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        vThreads.execute(() -> WorkspaceScopeHolder.run(sessionId, () -> {
            try {
                boolean isApproval = "/approve".equalsIgnoreCase(request.goal());
                boolean isDeny     = "/deny".equalsIgnoreCase(request.goal());

                if (isDeny) {
                    String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();
                    agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                        cp.setStatus(CheckpointStatus.FAILED);
                        cp.setFailedAt(Instant.now());
                        cp.setFailReason("Human denied tool execution via REST.");
                        agentStore.save(cp);
                    });
                    response.resume(Response.ok()
                            .entity(Map.of(
                                "status",  "DENIED",
                                "message", "Tool execution denied. Session terminated."
                            ))
                            .build());
                    return;
                }

                AutonomyLevel sessionLevel = heartbeatTracker.getAutonomyLevel(sessionId);
                if (sessionLevel == null) {
                    log.warnf("No stored autonomy level found for session %s, defaulting to SUPERVISED", sessionId);
                    sessionLevel = AutonomyLevel.SUPERVISED;
                }

                AutonomyLevel runLevel = isApproval ? AutonomyLevel.FULL : sessionLevel;

                String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();

                WorkspaceContext.run(sessionId, "rest:api", sessionId, runLevel, canonicalRole, ChannelType.REST_API, () -> {
                    MessageContext context = createRestContext(sessionId, request.goal());
                    context.setAutonomyLevel(runLevel);
                    if (isApproval) {
                        context.setResumeSignal(true);
                    }
                    AgentResult result = agentLoop.run(sessionId, canonicalRole,
                            request.goal(), reasoningEngine, maxIterations, context);
                    response.resume(responseMapper.mapResult(result, true));
                });

            } catch (BudgetExceededException e) {
                response.resume(Response.status(429).entity(Map.of("error", e.getMessage(), "code", "BUDGET")).build());
            } catch (BudgetWarningException e) {
                response.resume(Response.ok().entity(Map.of("warning", e.getMessage())).build());
            } catch (Exception e) {
                log.errorf("Async agent run threw exception for session %s: %s", sessionId, e.getMessage());
                log.debug("Full async agent failure stack trace:", e);
                response.resume(Response.serverError()
                        .entity(Map.of("status", "ERROR", "message",
                            e.getMessage() != null ? e.getMessage() : "Unknown technical fault"))
                        .build());
            }
        }));
    }

    public Response approve(String sessionId, String agentRole) {
        log.infof("REST approve for session %s role %s", sessionId, agentRole);

        var sessionError = validateSession(sessionId);
        if (sessionError.isPresent()) return sessionError.get();

        var roleError = validateRole(agentRole);
        if (roleError.isPresent()) return roleError.get();

        try {
            return WorkspaceScopeHolder.call(sessionId, () -> {
                String canonicalRole = AgentRoleEnum.fromString(agentRole).toScopedName();
                var checkpoint = agentStore.load(sessionId, canonicalRole);
                if (checkpoint.isEmpty() || checkpoint.get().getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "No pending approval for this session"))
                            .build();
                }

                MDC.put(MdcKeys.USER_ID, "rest:api");
                return WorkspaceContext.run(sessionId, "rest:api", sessionId, AutonomyLevel.FULL, canonicalRole, ChannelType.REST_API, () -> {
                    String resumeGoal = checkpointResumeHandler.resolveResumeGoal(sessionId, canonicalRole);
                    MessageContext context = createRestContext(sessionId, resumeGoal);
                    context.setResumeSignal(true);
                    AgentResult result = agentLoop.run(sessionId, agentRole, resumeGoal, reasoningEngine, 1, context);
                    return responseMapper.mapResult(result, false);
                });
            });
        } catch (BudgetExceededException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage(), "code", "BUDGET")).build();
        } catch (BudgetWarningException e) {
            return Response.ok().entity(Map.of("warning", e.getMessage())).build();
        } catch (Exception e) {
            log.errorf("Failed to approve session %s: %s", sessionId, e.getMessage());
            log.debug("Full approval failure stack trace:", e);
            return Response.serverError().entity(e.getMessage()).build();
        } finally {
            MDC.remove(MdcKeys.USER_ID);
        }
    }

    public Response deny(String sessionId, String agentRole) {
        var sessionError = validateSession(sessionId);
        if (sessionError.isPresent()) return sessionError.get();

        var roleError = validateRole(agentRole);
        if (roleError.isPresent()) return roleError.get();

        try {
            return WorkspaceScopeHolder.call(sessionId, () -> {
                String canonicalRole = AgentRoleEnum.fromString(agentRole).toScopedName();
                var checkpoint = agentStore.load(sessionId, canonicalRole);
                if (checkpoint.isEmpty() || checkpoint.get().getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "No pending approval for this session"))
                            .build();
                }

                MDC.put(MdcKeys.USER_ID, "rest:api");
                agentStore.markFailed(checkpoint.get().getId(), "Human denied via REST");
                return Response.ok(Map.of("status", "DENIED", "session", sessionId)).build();
            });
        } catch (Exception e) {
            log.errorf("Failed to deny session %s: %s", sessionId, e.getMessage());
            log.debug("Full denial failure stack trace:", e);
            return Response.serverError().entity(e.getMessage()).build();
        } finally {
            MDC.remove(MdcKeys.USER_ID);
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
                            return Response.ok(new SessionStatusResponse(
                                    sessionId, cp.getStatus().name(), steps, cp.getFailReason())).build();
                        } catch (Exception e) {
                            log.errorf("Failed to load steps for session %s: %s", sessionId, e.getMessage());
                            log.debug("Full load steps failure stack trace:", e);
                            return Response.serverError().entity(e.getMessage()).build();
                        }
                    })
                    .orElseGet(() -> Response.ok(Map.of(
                            "sessionId", sessionId,
                            "role", canonicalRole,
                            "status", CheckpointStatus.NOT_STARTED.name(),
                            "steps", List.of()
                    )).build());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", e.getMessage(), "code", "INVALID_ROLE"))
                .build();
        }
    }

    public Response terminateSession(String sessionId) {
        log.infof("Terminating session:  %s", sessionId);
        workspaceManager.terminateSession(sessionId);
        return Response.noContent().build();
    }

    private MessageContext createRestContext(String sessionId, String goal) {
        ChannelUser user = new ChannelUser("rest:api", "rest:api", "REST API User", ChannelType.REST_API);
        ChannelMessage msg = new ChannelMessage.RestApiMessage(user, goal, "rest-api-" + sessionId, Instant.now());
        ChannelSender noOpSender = new ChannelSender() {
            @Override public ChannelType supports() { return ChannelType.REST_API; }
            @Override public void send(ChannelUser recipient, List<ReplyNode> nodes) {
                log.infof("[REST-SENDER] Sent %d nodes to %s", nodes.size(), recipient.platformId());
            }
            @Override public void sendRaw(ChannelUser recipient, String text) {
                log.infof("[REST-SENDER] Sent raw message to %s: %s", recipient.platformId(), text);
            }
        };
        return new MessageContext("rest-chat-" + sessionId, user, msg, noOpSender);
    }

    private Optional<Response> validateRole(String role) {
        if (!AgentRoleEnum.isKnownRole(role)) {
            return Optional.of(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Unknown agent role: " + role, "code", "INVALID_ROLE"))
                    .build());
        }
        return Optional.empty();
    }

    private Optional<Response> validateSession(String sessionId) {
        if (!heartbeatTracker.exists(sessionId)) {
            return Optional.of(Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of(
                        "error", "Session not found: " + sessionId,
                        "code",  "SESSION_NOT_FOUND"
                    ))
                    .build());
        }
        return Optional.empty();
    }

    /**
     * Routes slash commands AND non-slash reflex prefixes (e.g. "deletecron")
     * directly to the reflex pipeline, bypassing the agent loop entirely.
     *
     * Previously only slash-prefixed goals were checked here, causing reflexes
     * like DeleteCronReflex (which match "deletecron <id>" without a slash) to
     * be silently skipped and handed to the LLM instead.
     */
    private Optional<Response> handleSlashCommand(String sessionId, RunAgentRequest request) {
        String goal = request.goal();
        if (goal == null) return Optional.empty();

        String lower = goal.trim().toLowerCase();
        boolean isSlash = lower.startsWith("/");
        boolean isNonSlashReflex = NON_SLASH_REFLEX_PREFIXES.stream()
                .anyMatch(lower::startsWith);

        if (!isSlash && !isNonSlashReflex) return Optional.empty();

        MessageContext context = createRestContext(sessionId, goal);

        try {
            String canonicalRole = AgentRoleEnum.fromString(request.agentRole()).toScopedName();

            ReflexResult result = WorkspaceContext.run(
                sessionId, "rest:api", sessionId, AutonomyLevel.FULL,
                canonicalRole, ChannelType.REST_API,
                () -> reflexRegistry.processIfReflex(
                    sessionId, "rest:api", sessionId, goal, context)
            );

            return result.commandResponse()
                    .map(response -> Response.ok(Map.of(
                        "status", "OK",
                        "message", response
                    )).build());

        } catch (Exception e) {
            log.errorf("Slash command failed for session %s: %s", sessionId, e.getMessage());
            log.debug("Full slash command failure stack trace:", e);
            return Optional.empty(); // fall through to agent loop
        }
    }

    private AutonomyLevel getOrDefaultAutonomy(String sessionId) {
        AutonomyLevel level = heartbeatTracker.getAutonomyLevel(sessionId);
        if (level != null) return level;

        return agentStore.loadLatestAutonomyForSession(sessionId)
                .orElseGet(() -> {
                    log.warnf("No stored autonomy found for session %s, defaulting to SUPERVISED", sessionId);
                    return AutonomyLevel.SUPERVISED;
                });
    }

    private static class AwaitingApprovalException extends RuntimeException {
        AwaitingApprovalException(String sessionId) {
            super("Session awaiting approval: " + sessionId);
        }
    }
}