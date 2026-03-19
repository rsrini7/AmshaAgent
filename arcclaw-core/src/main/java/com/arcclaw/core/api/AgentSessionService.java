package com.arcclaw.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.api.dto.SessionStatusResponse;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.DevSecretSeeder;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;

import io.quarkus.virtual.threads.VirtualThreads;
import com.arcclaw.core.util.MdcKeys;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class AgentSessionService {

    private final Logger log;
    private final AgentLoop agentLoop;
    private final ReasoningEngine reasoningEngine;
    private final StatefulAgentStore agentStore;
    private final WorkspaceManager workspaceManager;
    private final SessionHeartbeatTracker heartbeatTracker;
    private final DevSecretSeeder devSeeder;
    private final AgentResponseMapper responseMapper;
    private final ExecutorService vThreads;

    protected AgentSessionService() {
        this.log = null;
        this.agentLoop = null;
        this.reasoningEngine = null;
        this.agentStore = null;
        this.workspaceManager = null;
        this.heartbeatTracker = null;
        this.devSeeder = null;
        this.responseMapper = null;
        this.vThreads = null;
    }

    @Inject
    public AgentSessionService(Logger log,
                               AgentLoop agentLoop,
                               ReasoningEngine reasoningEngine,
                               StatefulAgentStore agentStore,
                               WorkspaceManager workspaceManager,
                               SessionHeartbeatTracker heartbeatTracker,
                               DevSecretSeeder devSeeder,
                               AgentResponseMapper responseMapper,
                               @VirtualThreads ExecutorService vThreads) {
        this.log = log;
        this.agentLoop = agentLoop;
        this.reasoningEngine = reasoningEngine;
        this.agentStore = agentStore;
        this.workspaceManager = workspaceManager;
        this.heartbeatTracker = heartbeatTracker;
        this.devSeeder = devSeeder;
        this.responseMapper = responseMapper;
        this.vThreads = vThreads;
    }

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        log.infof("Creating new session: {}", sessionId);
        heartbeatTracker.recordHeartbeat(sessionId);
        return sessionId;
    }

    public Response runSync(String sessionId, RunAgentRequest request) {
        log.infof("Running agent sync for session: {}, role: {}, goal: {}", sessionId, request.agentRole(),
                request.goal());
        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        try {
            return WorkspaceScopeHolder.call(sessionId, () -> {
                devSeeder.seedDevSecrets();
                return WorkspaceContext.run(
                        sessionId, "rest:sync", null, AutonomyLevel.SUPERVISED, request.agentRole(),
                        () -> {
                            MessageContext context = createRestContext(sessionId, request.goal());
                            AgentResult result = agentLoop.run(sessionId, request.agentRole(), request.goal(), reasoningEngine, maxIterations, context);
                            return responseMapper.mapResult(result, false);
                        }
                );
            });
        } catch (Exception e) {
            log.errorf("Sync agent run threw exception for session {}: {}", sessionId, e.getMessage(), e);
            return Response.serverError()
                    .entity(e.getMessage() != null ? e.getMessage() : "Unknown technical fault")
                    .build();
        }
    }

    public void runAsync(String sessionId, RunAgentRequest request, AsyncResponse response) {
        log.infof("Submitting async agent run to @VirtualThreads executor, session: {}", sessionId);
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

                devSeeder.seedDevSecrets();

                AutonomyLevel runLevel = isApproval ? AutonomyLevel.FULL : AutonomyLevel.SUPERVISED;

                WorkspaceContext.run(sessionId, "rest:async", null, runLevel, request.agentRole(), () -> {
                    MessageContext context = createRestContext(sessionId, request.goal());
                    if (isApproval) {
                        context.setResumeSignal(true);
                    }
                    AgentResult result = agentLoop.run(sessionId, request.agentRole(),
                            request.goal(), reasoningEngine, maxIterations, context);
                    response.resume(responseMapper.mapResult(result, true));
                });

            } catch (Exception e) {
                log.errorf("Async agent run threw exception for session {}: {}", sessionId, e.getMessage(), e);
                response.resume(Response.serverError()
                        .entity(Map.of("status", "ERROR", "message",
                            e.getMessage() != null ? e.getMessage() : "Unknown technical fault"))
                        .build());
            }
        }));
    }

    public Response approve(String sessionId, String agentRole) {
        log.infof("REST approve for session %s role %s", sessionId, agentRole);
        try {
            return WorkspaceScopeHolder.call(sessionId, () -> {
                String canonicalRole = AgentRoleEnum.fromString(agentRole).toScopedName();
                var checkpoint = agentStore.load(sessionId, canonicalRole);
                if (checkpoint.isEmpty() || checkpoint.get().getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "No pending approval for this session"))
                            .build();
                }

                MDC.put(MdcKeys.USER_ID, "rest:approve");
                return WorkspaceContext.run(sessionId, "rest:approve", null, AutonomyLevel.FULL, canonicalRole, () -> {
                    MessageContext context = createRestContext(sessionId, AgentSentinels.GOAL_RESUME_APPROVED);
                    context.setResumeSignal(true);
                    AgentResult result = agentLoop.run(sessionId, agentRole, AgentSentinels.GOAL_RESUME_APPROVED, reasoningEngine, 1, context);
                    return responseMapper.mapResult(result, false);
                });
            });
        } catch (Exception e) {
            log.errorf("Failed to approve session " + sessionId, e);
            return Response.serverError().entity(e.getMessage()).build();
        } finally {
            MDC.remove(MdcKeys.USER_ID);
        }
    }

    public Response deny(String sessionId, String agentRole) {
        log.infof("REST deny for session %s role %s", sessionId, agentRole);
        try {
            return WorkspaceScopeHolder.call(sessionId, () -> {
                String canonicalRole = AgentRoleEnum.fromString(agentRole).toScopedName();
                var checkpoint = agentStore.load(sessionId, canonicalRole);
                if (checkpoint.isEmpty() || checkpoint.get().getStatus() != CheckpointStatus.AWAITING_APPROVAL) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "No pending approval for this session"))
                            .build();
                }

                MDC.put(MdcKeys.USER_ID, "rest:deny");
                agentStore.markFailed(checkpoint.get().getId(), "Human denied via REST");
                heartbeatTracker.removeHeartbeat(sessionId);
                return Response.ok(Map.of("status", "DENIED", "session", sessionId)).build();
            });
        } catch (Exception e) {
            log.error("Failed to deny session " + sessionId, e);
            return Response.serverError().entity(e.getMessage()).build();
        } finally {
            MDC.remove(MdcKeys.USER_ID);
        }
    }

    public Response getStatus(String sessionId, String role) {
        String canonicalRole = AgentRoleEnum.fromString(role).toScopedName();
        return agentStore.load(sessionId, canonicalRole)
                .map(cp -> {
                    try {
                        List<ReasoningStep> steps = agentStore.loadSteps(cp.getId());
                        return Response.ok(new SessionStatusResponse(
                                sessionId, cp.getStatus().name(), steps, cp.getFailReason())).build();
                    } catch (Exception e) {
                        log.errorf("Failed to load steps for session %s: %s",
                                sessionId, e.getMessage(), e);
                        return Response.serverError().entity(e.getMessage()).build();
                    }
                })
                .orElseGet(() -> Response.ok(Map.of(
                        "sessionId", sessionId,
                        "role", canonicalRole,
                        "status", CheckpointStatus.NOT_STARTED.name(),
                        "steps", List.of()
                )).build());
    }


    public Response terminateSession(String sessionId) {
        log.infof("Terminating session: {}", sessionId);
        workspaceManager.terminateSession(sessionId);
        return Response.noContent().build();
    }

    private MessageContext createRestContext(String sessionId, String goal) {
        ChannelUser user = new ChannelUser("rest:api", "REST API User", ChannelType.REST_API);
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
}
