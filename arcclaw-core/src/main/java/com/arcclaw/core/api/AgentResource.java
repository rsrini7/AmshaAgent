package com.arcclaw.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.api.dto.CreateSessionResponse;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.virtual.threads.VirtualThreads;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/sessions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class AgentResource {

    public record ApprovalRequest(String agentRole) {}
    public record AgentResponse(String answer, int totalInputTokens, int totalOutputTokens) {}
    public record ApprovalRequiredResponse(String toolName, String paramsJson, String sessionId) {}
    public record ErrorResponse(String reason, String code) {}

    @Inject
    Logger log;


    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    AgentLoop agentLoop;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    @VirtualThreads
    java.util.concurrent.ExecutorService vThreads;

    @Inject
    DevSecretSeeder devSeeder;

    @POST
    public Response createSession() {
        String sessionId = UUID.randomUUID().toString();
        log.infof("Creating new session: {}", sessionId);
        heartbeatTracker.recordHeartbeat(sessionId);
        return Response.status(Response.Status.CREATED)
                .entity(new CreateSessionResponse(sessionId))
                .build();
    }

    @POST
    @Path("/{id}/run")
    public Response runAgentSync(@PathParam("id") String sessionId, @Valid RunAgentRequest request) {
        log.infof("Running agent sync for session: {}, role: {}, goal: {}", sessionId, request.agentRole(),
                request.goal());
        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        try {
            return WorkspaceScopeHolder.call(sessionId, () -> {

                devSeeder.seedDevSecrets(); // no-op when auto-seed-secrets=false

                return WorkspaceContext.run(
                        sessionId, "rest:sync", null, AutonomyLevel.SUPERVISED, request.agentRole(),
                        () -> {
                            AgentResult result = agentLoop.run(sessionId, request.agentRole(), request.goal(), reasoningEngine, maxIterations);
                            return switch (result) {
                                case AgentResult.Finished f -> Response.ok(new AgentResponse(
                                        f.answer(), f.totalInputTokens(), f.totalOutputTokens())).build();
                                case AgentResult.AwaitingApproval a -> Response.status(202).entity(
                                        new ApprovalRequiredResponse(a.toolName(), a.paramsJson(), a.sessionId())).build();
                                case AgentResult.LoopExceeded l -> Response.status(408).entity(
                                        new ErrorResponse("Iteration limit " + l.maxIterations() + " reached", "LOOP_EXCEEDED")).build();
                                case AgentResult.Failed f -> mapFailedResponse(f);
                            };
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

    @POST
    @Path("/{id}/run-async")
    public void runAgentAsync(@PathParam("id") String sessionId, @Valid RunAgentRequest request,
            @Suspended AsyncResponse response) {
        log.infof("Submitting async agent run to @VirtualThreads executor, session: {}", sessionId);
        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        // --- ARCHITECTURE NOTE: Context Propagation ---
        // We intentionally DO NOT use WorkspaceExecutors.wrap(vThreads) here.
        // This method is a system entry point (REST boundary). The calling HTTP 
        // worker thread does not yet have an active workspace session to propagate. 
        // Instead of inheriting context, we are establishing the root lexical 
        // scope from scratch using WorkspaceScopeHolder.run(sessionId, ...).
        //
        // WHERE TO USE wrap():
        // WorkspaceExecutors.wrap() should only be used deep inside the agent 
        // kernel (e.g., AgentLoop parallelizing multiple Tool executions), where 
        // a parent thread already has an active session that must be seamlessly 
        // inherited by asynchronous child tasks.
        // ----------------------------------------------
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

                devSeeder.seedDevSecrets(); // no-op when auto-seed-secrets=false

                AutonomyLevel runLevel = isApproval ? AutonomyLevel.FULL : AutonomyLevel.SUPERVISED;

                WorkspaceContext.run(sessionId, "rest:async", null, runLevel, request.agentRole(), () -> {
                    AgentResult result = agentLoop.run(sessionId, request.agentRole(),
                            request.goal(), reasoningEngine, maxIterations);

                    Response res = switch (result) {
                        case AgentResult.Finished f -> Response.accepted(new AgentResponse(
                                f.answer(), f.totalInputTokens(), f.totalOutputTokens())).build();
                        case AgentResult.AwaitingApproval a -> Response.status(202).entity(
                                new ApprovalRequiredResponse(a.toolName(), a.paramsJson(), a.sessionId())).build();
                        case AgentResult.LoopExceeded l -> Response.status(408).entity(
                                new ErrorResponse("Iteration limit " + l.maxIterations() + " reached", "LOOP_EXCEEDED")).build();
                        case AgentResult.Failed f -> mapFailedResponse(f);
                    };
                    response.resume(res);
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

    @GET
    @Path("/{id}/status/{role}")
    public Response getSessionStatus(@PathParam("id") String sessionId, @PathParam("role") String role) {
        String canonicalRole = AgentRoleEnum.fromString(role).toScopedName();
        return agentStore.load(sessionId, canonicalRole)
                .map(cp -> {
                    try {
                        // 1. Default to an empty list
                        List<ReasoningStep> steps = List.of();
                        
                        // 2. Safely check if stateJson actually contains data before parsing
                        if (cp.getStateJson() != null && !cp.getStateJson().isBlank()) {
                            steps = objectMapper.readValue(cp.getStateJson(), new TypeReference<List<ReasoningStep>>() {});
                        }
                        
                        return Response.ok(new SessionStatusResponse(sessionId, cp.getStatus().name(), steps, cp.getFailReason())).build();
                    } catch (Exception e) {
                        log.errorf("Failed to parse stateJson for session %s: %s", sessionId, e.getMessage(), e);
                        return Response.serverError().entity(e.getMessage()).build();
                    }
                })
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/{id}")
    public Response terminateSession(@PathParam("id") String sessionId) {
        log.infof("Terminating session: {}", sessionId);
        workspaceManager.terminateSession(sessionId);
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/approve")
    public Response approveSession(
            @PathParam("id") String sessionId,
            @QueryParam("agentRole") @DefaultValue("coordinator") String agentRole) {
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

                MDC.put("userId", "rest:approve");
                return WorkspaceContext.run(sessionId, "rest:approve", null, AutonomyLevel.FULL, canonicalRole, () -> {
                    AgentResult result = agentLoop.run(sessionId, agentRole, "approve", reasoningEngine, 1);
                    return switch (result) {
                        case AgentResult.Finished f -> Response.ok(new AgentResponse(
                                f.answer(), f.totalInputTokens(), f.totalOutputTokens())).build();
                        case AgentResult.AwaitingApproval a -> Response.status(202).entity(
                                new ApprovalRequiredResponse(a.toolName(), a.paramsJson(), a.sessionId())).build();
                        case AgentResult.LoopExceeded l -> Response.status(408).entity(
                                new ErrorResponse("Iteration limit " + l.maxIterations() + " reached", "LOOP_EXCEEDED")).build();
                        case AgentResult.Failed f -> mapFailedResponse(f);
                    };
                });
            });
        } catch (Exception e) {
            log.errorf("Failed to approve session " + sessionId, e);
            return Response.serverError().entity(e.getMessage()).build();
        } finally {
            MDC.remove("userId");
        }
    }

    @POST
    @Path("/{id}/deny")
    public Response denySession(
            @PathParam("id") String sessionId,
            @QueryParam("agentRole") @DefaultValue("coordinator") String agentRole) {
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

                MDC.put("userId", "rest:deny");
                agentStore.markFailed(checkpoint.get().getId(), "Human denied via REST");
                heartbeatTracker.removeHeartbeat(sessionId);
                return Response.ok(Map.of("status", "DENIED", "session", sessionId)).build();
            });
        } catch (Exception e) {
            log.error("Failed to deny session " + sessionId, e);
            return Response.serverError().entity(e.getMessage()).build();
        } finally {
            MDC.remove("userId");
        }
    }
    private Response mapFailedResponse(AgentResult.Failed f) {
        return switch (f.category()) {
            case COMPLIANCE_VIOLATION -> Response.status(451)
                    .entity(new ErrorResponse(f.reason(), "COMPLIANCE")).build();
            case BUDGET_EXCEEDED -> Response.status(429)
                    .entity(new ErrorResponse(f.reason(), "BUDGET")).build();
            case USER_DENIED -> Response.status(422)
                    .entity(new ErrorResponse(f.reason(), "USER_DENIED")).build();
            case SCHEMA_ERROR -> Response.status(422)
                    .entity(new ErrorResponse(f.reason(), "SCHEMA")).build();
            case SYSTEM_ERROR -> Response.serverError()
                    .entity(new ErrorResponse(f.reason(), "SYSTEM")).build();
            case RATE_LIMIT -> Response.serverError()
                    .entity(new ErrorResponse(f.reason(), "RATE_LIMIT")).build();                    
            
        };
    }
}
