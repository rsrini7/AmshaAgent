package com.arcclaw.app.ui;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.app.security.ApiKeyValidator;
import com.arcclaw.app.ui.dto.CheckpointApprovalRequest;
import com.arcclaw.app.ui.dto.CheckpointApprovalResponse;
import com.arcclaw.app.ui.dto.PendingApprovalDto;
import com.arcclaw.core.agent.sse.AgentSseService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Multi;
import org.jboss.resteasy.reactive.RestStreamElementType;

@ApplicationScoped
@Path("hitl")
@RunOnVirtualThread
@Produces(MediaType.APPLICATION_JSON)
public class HitlResource {

    @Inject HitlService hitlService;
    @Inject AgentSseService agentSseService;
    @Inject ApiKeyValidator apiKeyValidator;

    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<AgentSseService.SseEvent> hitlEvents(@QueryParam("sessionId") String sessionId, @QueryParam("apiKey") String apiKey) {
        if (!apiKeyValidator.isValid(apiKey)) {
            return Multi.createFrom().failure(
            new WebApplicationException(Response.Status.UNAUTHORIZED));
         }
        if (sessionId == null || sessionId.isBlank()) {
            return Multi.createFrom().failure(
                new WebApplicationException(Response.Status.BAD_REQUEST));
        }
        return agentSseService.subscribe(sessionId);
    }

    @GET
    public Response listPending(@jakarta.ws.rs.HeaderParam("X-ARCCLAW-API-KEY") String apiKey,
                                @QueryParam("apiKey") String apiKeyParam) {
        // Header first, fall back to query param (same pattern as ApiKeyAuthFilter)
        String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : apiKeyParam;
        if (!apiKeyValidator.isValid(key)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Missing or invalid API key"))
                    .build();
        }
        String userId = apiKeyValidator.resolveUserId(key);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Cannot resolve user identity"))
                    .build();
        }

        List<PendingApprovalDto> result = hitlService.getPendingApprovals(userId, 50).values().stream()
            .map(pending -> new PendingApprovalDto(
                pending.taskId(),
                pending.toolName(),
                pending.params(),
                pending.requestedAt(),
                pending.agentRole()
            ))
            .collect(Collectors.toList());
        return Response.ok(result).build();
    }

    @POST
    @Path("{taskId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response approve(@PathParam("taskId") String taskId, CheckpointApprovalRequest request) {
        String toolName = (request != null) ? request.toolName() : null;
        String reason = (request != null) ? request.reason() : "Approved via REST";
        try {
            hitlService.resolve(taskId, toolName, true, reason);
            return Response.ok(new CheckpointApprovalResponse("approved", taskId, toolName, reason)).build();
        } catch (Exception e) {
            return handleException(taskId, e);
        }
    }

    @POST
    @Path("{taskId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reject(@PathParam("taskId") String taskId, CheckpointApprovalRequest request) {
        String toolName = (request != null) ? request.toolName() : null;
        String reason = (request != null && request.reason() != null && !request.reason().isBlank()) 
            ? request.reason() : "Rejected by user";
        try {
            hitlService.resolve(taskId, toolName, false, reason);
            return Response.ok(new CheckpointApprovalResponse("rejected", taskId, toolName, reason)).build();
        } catch (Exception e) {
            return handleException(taskId, e);
        }
    }

    private Response handleException(String taskId, Exception e) {
        if (e.getMessage() != null && e.getMessage().contains("No pending")) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "No pending approval found", "taskId", taskId))
                .build();
        }
        if (e.getMessage() != null && e.getMessage().contains("already resolved")) {
            return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "Already resolved", "taskId", taskId))
                .build();
        }
        return Response.serverError()
            .entity(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"))
            .build();
    }
}
