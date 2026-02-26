package com.arcclaw.core.api;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.agent.ReasoningStep;
import com.arcclaw.core.api.dto.CreateSessionResponse;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.api.dto.SessionStatusResponse;
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
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jboss.logging.MDC;
import org.jboss.logging.Logger;

@ApplicationScoped
@Path("/sessions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class AgentResource {

    public record ApprovalRequest(String agentRole) {}

    @Inject
    Logger log;

    @Inject
    WorkspaceContext workspaceContext;

    @Inject
    WorkspaceManager workspaceManager;

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
    Instance<DevSecretSeeder> devSeeder;

    @POST
    public Response createSession() {
        String sessionId = UUID.randomUUID().toString();
        log.infof("Creating new session: {}", sessionId);
        workspaceManager.recordHeartbeat(sessionId);
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
            // 1. Activate the Workspace Scope for this REST request thread
            WorkspaceScopeHolder.set(sessionId);
            
            // 2. Elevate Autonomy so tools trigger RequiresApprovalException instead of hard crashing
            workspaceContext.setAutonomyLevel(AutonomyLevel.SUPERVISED);

            if (!devSeeder.isUnsatisfied()) {
                devSeeder.get().seedDevSecrets();
            }

            AgentResult result = agentLoop.run(sessionId, request.agentRole(), request.goal(), reasoningEngine,
                    maxIterations);
            if (result.success()) {
                return Response.ok(result).build();
            } else {
                log.errorf("Sync agent run completed with failure for session {}: {}", sessionId,
                        result.terminationReason());
                return Response.serverError()
                        .entity(result.terminationReason())
                        .build();
            }
        } catch (Exception e) {
            log.errorf("Sync agent run threw exception for session {}: {}", sessionId, e.getMessage(), e);
            return Response.serverError()
                    .entity(e.getMessage() != null ? e.getMessage() : "Unknown technical fault")
                    .build();
        } finally {
            // 3. ALWAYS clear the thread scope when done to prevent memory leaks!
            WorkspaceScopeHolder.clear();
        }
    }

    @POST
    @Path("/{id}/run-async")
    public void runAgentAsync(@PathParam("id") String sessionId, @Valid RunAgentRequest request,
            @Suspended AsyncResponse response) {
        log.infof("Submitting async agent run to @VirtualThreads executor, session: {}", sessionId);
        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        vThreads.execute(() -> {
            try {
                // MUST be first — activates WorkspaceScoped context
                WorkspaceScopeHolder.set(sessionId);
                workspaceContext.setAutonomyLevel(AutonomyLevel.SUPERVISED); // ← safe now

                boolean isApproval = "/approve".equalsIgnoreCase(request.goal());
                boolean isDeny     = "/deny".equalsIgnoreCase(request.goal());

                if (isDeny) {
                    agentStore.load(sessionId, request.agentRole()).ifPresent(cp -> {
                        cp.status     = CheckpointStatus.FAILED;
                        cp.failedAt   = Instant.now();
                        cp.failReason = "Human denied tool execution via REST.";
                        agentStore.save(cp);
                    });
                    response.resume(Response.ok()
                            .entity(Map.of(
                                "status",  "DENIED",
                                "message", "Tool execution denied. Session terminated."
                            ))
                            .build());
                    return; // finally still runs — scope clears cleanly
                }

                workspaceContext.setAutonomyLevel(
                    isApproval ? AutonomyLevel.FULL : AutonomyLevel.SUPERVISED
                );

                if (!devSeeder.isUnsatisfied()) {
                   devSeeder.get().seedDevSecrets();
                }

                AgentResult result = agentLoop.run(sessionId, request.agentRole(),
                        request.goal(), reasoningEngine, maxIterations);

                if (result.awaitingApproval()) {
                    response.resume(Response.accepted()
                            .entity(Map.of(
                                "status",  "AWAITING_APPROVAL",
                                "message", "Agent is pending tool approval. POST /approve or /deny as goal.",
                                "session", sessionId
                            ))
                            .build());
                } else if (result.success()) {
                    response.resume(Response.accepted()
                            .entity(Map.of("status", "COMPLETED", "answer", result.finalAnswer()))
                            .build());
                } else {
                    log.errorf("Async agent run failed for session {}: {}", sessionId, result.terminationReason());
                    response.resume(Response.serverError()
                            .entity(Map.of("status", "FAILED", "reason", result.terminationReason()))
                            .build());
                }

            } catch (Exception e) {
                log.errorf("Async agent run threw exception for session {}: {}", sessionId, e.getMessage(), e);
                response.resume(Response.serverError()
                        .entity(Map.of("status", "ERROR", "message",
                            e.getMessage() != null ? e.getMessage() : "Unknown technical fault"))
                        .build());
            } finally {
                 // Guard: only call setAutonomyLevel if scope is still active
                try {
                    workspaceContext.setAutonomyLevel(AutonomyLevel.SUPERVISED);
                } catch (jakarta.enterprise.context.ContextNotActiveException ignored) {
                    // Scope already cleared or was never activated — safe to ignore
                }
                WorkspaceScopeHolder.clear();
            }
        });
    }

    @GET
    @Path("/{id}/status/{role}")
    public Response getSessionStatus(@PathParam("id") String sessionId, @PathParam("role") String role) {
        return agentStore.load(sessionId, role)
                .map(cp -> {
                    try {
                        // 1. Default to an empty list
                        List<ReasoningStep> steps = List.of();
                        
                        // 2. Safely check if stateJson actually contains data before parsing
                        if (cp.stateJson != null && !cp.stateJson.isBlank()) {
                            steps = objectMapper.readValue(cp.stateJson, new TypeReference<List<ReasoningStep>>() {});
                        }
                        
                        return Response.ok(new SessionStatusResponse(sessionId, cp.status.name(), steps, cp.failReason)).build();
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
            WorkspaceScopeHolder.set(sessionId);
            workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);

            var checkpoint = agentStore.load(sessionId, agentRole);
            if (checkpoint.isEmpty() || checkpoint.get().status != CheckpointStatus.AWAITING_APPROVAL) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "No pending approval for this session"))
                        .build();
            }

            MDC.put("userId", "rest:approve");
            agentLoop.run(sessionId, agentRole, "approve", reasoningEngine, 1);
            return Response.ok(Map.of("status", "APPROVED", "session", sessionId)).build();
        } catch (Exception e) {
            log.errorf("Failed to approve session " + sessionId, e);
            return Response.serverError().entity(e.getMessage()).build();
        } finally {
            WorkspaceScopeHolder.clear();
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
            WorkspaceScopeHolder.set(sessionId);
            var checkpoint = agentStore.load(sessionId, agentRole);
            if (checkpoint.isEmpty() || checkpoint.get().status != CheckpointStatus.AWAITING_APPROVAL) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "No pending approval for this session"))
                        .build();
            }

            MDC.put("userId", "rest:deny");
            agentStore.markFailed(checkpoint.get().id, "Human denied via REST");
            workspaceManager.removeHeartbeat(sessionId);
            return Response.ok(Map.of("status", "DENIED", "session", sessionId)).build();
        } catch (Exception e) {
            log.error("Failed to deny session " + sessionId, e);
            return Response.serverError().entity(e.getMessage()).build();
        } finally {
            WorkspaceScopeHolder.clear();
            MDC.remove("userId");
        }
    }
}
