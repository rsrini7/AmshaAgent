package com.arcclaw.core.api;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.api.dto.CreateSessionResponse;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.api.dto.SessionStatusResponse;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.state.StatefulAgentStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.virtual.threads.VirtualThreads;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@Path("/sessions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class AgentResource {

    private static final Logger log = LoggerFactory.getLogger(AgentResource.class);

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

    @POST
    public Response createSession() {
        String sessionId = UUID.randomUUID().toString();
        log.info("Creating new session: {}", sessionId);
        workspaceManager.recordHeartbeat(sessionId);
        return Response.status(Response.Status.CREATED)
                .entity(new CreateSessionResponse(sessionId))
                .build();
    }

    @POST
    @Path("/{id}/run")
    public Response runAgentSync(@PathParam("id") String sessionId, @Valid RunAgentRequest request) {
        log.info("Running agent sync for session: {}, role: {}, goal: {}", sessionId, request.agentRole(),
                request.goal());
        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        AgentResult result = agentLoop.run(sessionId, request.agentRole(), request.goal(), reasoningEngine,
                maxIterations);
        return Response.ok(result).build();
    }

    @POST
    @Path("/{id}/run-async")
    public void runAgentAsync(@PathParam("id") String sessionId, @Valid RunAgentRequest request,
            @Suspended AsyncResponse response) {
        log.info("Submitting async agent run to @VirtualThreads executor, session: {}", sessionId);
        int maxIterations = request.maxIterations() != null ? request.maxIterations() : 5;

        // Use Quarkus-managed @VirtualThreads executor to propagate Vert.x duplicated
        // context
        // (which carries CDI RequestScope) into the spawned virtual thread.
        vThreads.execute(() -> {
            try {
                AgentResult result = agentLoop.run(sessionId, request.agentRole(), request.goal(), reasoningEngine,
                        maxIterations);
                if (result.success()) {
                    response.resume(Response.accepted().build());
                } else {
                    log.error("Async agent run completed with failure for session {}: {}", sessionId,
                            result.terminationReason());
                    response.resume(Response.serverError()
                            .entity(java.util.Map.of("error", result.terminationReason()))
                            .build());
                }
            } catch (Exception e) {
                log.error("Async agent run threw exception for session {}: {}", sessionId, e.getMessage(), e);
                response.resume(Response.serverError().entity(
                        java.util.Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown technical fault"))
                        .build());
            }
        });
    }

    @GET
    @Path("/{id}/status/{role}")
    public Response getSessionStatus(@PathParam("id") String sessionId, @PathParam("role") String role) {
        return agentStore.load(sessionId, role)
                .map(cp -> {
                    try {
                        List<com.arcclaw.core.agent.ReasoningStep> steps = objectMapper.readValue(
                                cp.stateJson,
                                new TypeReference<List<com.arcclaw.core.agent.ReasoningStep>>() {
                                });
                        return Response.ok(new SessionStatusResponse(sessionId, cp.status.name(), steps)).build();
                    } catch (Exception e) {
                        log.error("Failed to deserialize state JSON for session {}", sessionId, e);
                        return Response.serverError().build();
                    }
                })
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/{id}")
    public Response terminateSession(@PathParam("id") String sessionId) {
        log.info("Terminating session: {}", sessionId);
        workspaceManager.terminateSession(sessionId);
        return Response.noContent().build();
    }
}
