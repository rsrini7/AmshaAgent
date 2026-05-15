package com.arcclaw.app.api;

import java.util.Map;

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

import com.arcclaw.core.api.AgentSessionService;
import com.arcclaw.core.api.dto.CreateSessionRequest;
import com.arcclaw.core.api.dto.CreateSessionResponse;
import com.arcclaw.core.api.dto.RunAgentRequest;
import io.smallrye.common.annotation.RunOnVirtualThread;
import org.eclipse.microprofile.openapi.annotations.Operation;

@ApplicationScoped
@Path("/sessions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class AgentResource {

    @Inject
    AgentSessionService sessionService;

    @POST
    @Operation(summary = "Create a new agent session with specified autonomy level")
    public Response createSession(@Valid CreateSessionRequest request) {
        var level = request != null ? request.autonomyLevel() : null;
        return Response.status(Response.Status.CREATED)
                .entity(new CreateSessionResponse(sessionService.createSession(level)))
                .build();
    }

    @GET
    @Operation(summary = "List all active agent sessions")
    public Response listSessions() {
        var sessions = sessionService.listSessions();
        return Response.ok(Map.of(
                "sessions", sessions.keySet(),
                "count", sessions.size()
        )).build();
    }

    @POST
    @Path("/{id}/run")
    @Operation(summary = "Run an agent loop synchronously for a given goal")
    public Response runAgentSync(@PathParam("id") String sessionId, @Valid RunAgentRequest request) {
        return sessionService.runSync(sessionId, request);
    }

    @POST
    @Path("/{id}/run-async")
    @Operation(summary = "Start an agent loop asynchronously")
    public void runAgentAsync(@PathParam("id") String sessionId, @Valid RunAgentRequest request,
            @Suspended AsyncResponse response) {
        sessionService.runAsync(sessionId, request, response);
    }

    @GET
    @Path("/{id}/status/{role}")
    @Operation(summary = "Get the current reasoning status and history for a role in a session")
    public Response getSessionStatus(@PathParam("id") String sessionId, @PathParam("role") String role) {
        return sessionService.getStatus(sessionId, role);
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Force terminate an active agent session")
    public Response terminateSession(@PathParam("id") String sessionId) {
        return sessionService.terminateSession(sessionId);
    }

    @POST
    @Path("/{id}/approve")
    @Operation(summary = "Provide manual human approval for a pending tool execution")
    public Response approveSession(
            @PathParam("id") String sessionId,
            @QueryParam("agentRole") @DefaultValue("coordinator") String agentRole) {
        return sessionService.approve(sessionId, agentRole);
    }

    @POST
    @Path("/{id}/deny")
    @Operation(summary = "Deny a pending tool execution and provide feedback to the agent")
    public Response denySession(
            @PathParam("id") String sessionId,
            @QueryParam("agentRole") @DefaultValue("coordinator") String agentRole) {
        return sessionService.deny(sessionId, agentRole);
    }
}
