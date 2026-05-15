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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.arcclaw.core.api.AgentResponseMapper;
import com.arcclaw.core.api.AgentSessionService;
import com.arcclaw.core.api.dto.ArcClawErrorResponse;
import com.arcclaw.core.api.dto.CreateSessionRequest;
import com.arcclaw.core.api.dto.CreateSessionResponse;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.api.dto.ServiceResult;
import com.arcclaw.core.scope.WorkspaceContext;
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

    @Inject
    AgentResponseMapper responseMapper;

    @Context
    SecurityContext securityContext;

    @POST
    @Operation(summary = "Create a new agent session with specified autonomy level")
    public Response createSession(@Valid CreateSessionRequest request) {
        var level = request != null ? request.autonomyLevel() : null;
        String userId = resolveUserId();
        return Response.status(Response.Status.CREATED)
                .entity(new CreateSessionResponse(sessionService.createSession(userId, level)))
                .build();
    }

    @GET
    @Operation(summary = "List all active agent sessions")
    public Response listSessions() {
        String userId = resolveUserId();
        var sessions = sessionService.listSessions(userId);
        return Response.ok(Map.of(
                "sessions", sessions.keySet(),
                "count", sessions.size()
        )).build();
    }

    @POST
    @Path("/{id}/run")
    @Operation(summary = "Run an agent loop synchronously for a given goal")
    public Response runAgentSync(@PathParam("id") String sessionId, @Valid RunAgentRequest request) {
        String userId = resolveUserId();
        var result = sessionService.runSync(sessionId, userId, request);
        return toResponse(result);
    }

    @POST
    @Path("/{id}/run-async")
    @Operation(summary = "Start an agent loop asynchronously")
    public void runAgentAsync(@PathParam("id") String sessionId, @Valid RunAgentRequest request,
            @Suspended AsyncResponse asyncResponse) {
        String userId = resolveUserId();
        sessionService.runAsync(sessionId, userId, request, result -> {
            asyncResponse.resume(toResponse(result));
        });
    }

    @GET
    @Path("/{id}/status/{role}")
    @Operation(summary = "Get the current reasoning status and history for a role in a session")
    public Response getSessionStatus(@PathParam("id") String sessionId, @PathParam("role") String role) {
        String userId = resolveUserId();
        var result = sessionService.getStatus(sessionId, userId, role);
        return toResponse(result);
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Force terminate an active agent session")
    public Response terminateSession(@PathParam("id") String sessionId) {
        String userId = resolveUserId();
        sessionService.terminateSession(sessionId, userId);
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/approve")
    @Operation(summary = "Provide manual human approval for a pending tool execution")
    public Response approveSession(
            @PathParam("id") String sessionId,
            @QueryParam("agentRole") @DefaultValue("coordinator") String agentRole) {
        String userId = resolveUserId();
        var result = sessionService.approve(sessionId, userId, agentRole);
        return toResponse(result);
    }

    @POST
    @Path("/{id}/deny")
    @Operation(summary = "Deny a pending tool execution and provide feedback to the agent")
    public Response denySession(
            @PathParam("id") String sessionId,
            @QueryParam("agentRole") @DefaultValue("coordinator") String agentRole) {
        String userId = resolveUserId();
        var result = sessionService.deny(sessionId, userId, agentRole);
        return toResponse(result);
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    private Response toResponse(ServiceResult<Object> result) {
        if (result.isSuccess()) {
            Object value = result.value();
            if (value instanceof AgentResponseMapper.MappedResult mr) {
                Object entity = mr.entity();
                return Response.status(mr.suggestedHttpStatus()).entity(entity).build();
            }
            return Response.ok(value).build();
        }
        return switch (result.errorCode()) {
            case "SESSION_NOT_FOUND" -> Response.status(Response.Status.NOT_FOUND)
                    .entity(new ArcClawErrorResponse(result.errorMessage())).build();
            case "SESSION_ACCESS_DENIED" -> Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ArcClawErrorResponse(result.errorMessage())).build();
            case "UNKNOWN_ROLE", "FORK_BOMB_REJECTED" -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ArcClawErrorResponse(result.errorMessage())).build();
            case "AWAITING_APPROVAL" -> Response.status(Response.Status.CONFLICT)
                    .entity(new ArcClawErrorResponse(result.errorMessage())).build();
            case "NO_PENDING_APPROVAL" -> Response.status(Response.Status.CONFLICT)
                    .entity(new ArcClawErrorResponse(result.errorMessage())).build();
            case "BUDGET" -> Response.status(429)
                    .entity(new ArcClawErrorResponse(result.errorMessage())).build();
            case "SYSTEM" -> Response.serverError()
                    .entity(new ArcClawErrorResponse(result.errorMessage())).build();
            default -> Response.serverError()
                    .entity(new ArcClawErrorResponse(result.errorMessage())).build();
        };
    }
}
