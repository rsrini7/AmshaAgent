package com.amsha.app.api;

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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.loop.AnswerQualityPrediction;
import com.amsha.core.agent.loop.ToolRiskPrediction;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.api.AgentResponseMapper;
import com.amsha.core.api.AgentSessionService;
import com.amsha.core.api.dto.ActiveAgentResponse;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.api.dto.CreateSessionRequest;
import com.amsha.core.api.dto.CreateSessionResponse;
import com.amsha.core.api.dto.RunAgentRequest;
import com.amsha.core.api.dto.ServiceResult;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.CheckpointCrudPort;
import com.amsha.persistence.api.CheckpointDto;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;

import io.smallrye.common.annotation.RunOnVirtualThread;

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

    @Inject
    CheckpointCrudPort checkpointCrud;

    @Context
    SecurityContext securityContext;

    @Context
    HttpHeaders httpHeaders;

    @ConfigProperty(name = "amsha.test-data-collection.enabled", defaultValue = "false")
    boolean collectionEnabled;

    private static final Set<String> TOOL_RISK_LABELS = Set.of(
            ToolRiskPrediction.LOW_RISK.name(),
            ToolRiskPrediction.REQUIRE_APPROVAL.name(),
            ToolRiskPrediction.BLOCK.name());
    private static final Set<String> ANSWER_QUALITY_LABELS =
            Set.of(AnswerQualityPrediction.ACCEPTABLE.name(), AnswerQualityPrediction.RETRY_RECOMMENDED.name());

    @POST
    @RunOnVirtualThread
    @Operation(summary = "Create a new agent session with specified autonomy level")
    public Response createSession(@Valid CreateSessionRequest request) {
        var level = request != null ? request.autonomyLevel() : null;
        String userId = resolveUserId();

        Map<String, String> expectedLabels = new HashMap<>();
        if (collectionEnabled
                && securityContext != null
                && securityContext.isUserInRole("internal")
                && httpHeaders != null) {
            try {
                putValidatedLabel(
                        expectedLabels,
                        "tool_risk",
                        httpHeaders.getHeaderString("X-Amsha-Test-Expected-Tool-Risk"),
                        TOOL_RISK_LABELS);
                putValidatedLabel(
                        expectedLabels,
                        "answer_quality",
                        httpHeaders.getHeaderString("X-Amsha-Test-Expected-Answer-Quality"),
                        ANSWER_QUALITY_LABELS);
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new AmshaErrorResponse(e.getMessage()))
                        .build();
            }
        }

        try {
            return Response.status(Response.Status.CREATED)
                    .entity(new CreateSessionResponse(sessionService.createSession(userId, level, expectedLabels)))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse(e.getMessage()))
                    .build();
        }
    }

    private static void putValidatedLabel(
            Map<String, String> expectedLabels, String task, String label, Set<String> allowed) {
        if (label == null) {
            return;
        }
        if (!allowed.contains(label)) {
            throw new IllegalArgumentException(
                    "Invalid " + task + " label '" + label + "'; expected one of " + allowed);
        }
        expectedLabels.put(task, label);
    }

    @GET
    @RunOnVirtualThread
    @Operation(summary = "List all active agent sessions")
    public Response listSessions() {
        String userId = resolveUserId();
        var sessions = sessionService.listSessions(userId);
        return Response.ok(Map.of(
                        "sessions", sessions.keySet(),
                        "count", sessions.size()))
                .build();
    }

    @GET
    @Path("/active")
    @RunOnVirtualThread
    @Operation(summary = "List all active agent instances across all sessions")
    public Response listActiveAgents() {
        List<CheckpointDto> active = checkpointCrud.findAllActive();
        List<ActiveAgentResponse.AgentSummary> summaries = active.stream()
                .map(cp -> new ActiveAgentResponse.AgentSummary(
                        cp.id(),
                        cp.sessionId(),
                        cp.agentRole(),
                        cp.status(),
                        cp.autonomyLevel(),
                        cp.depth(),
                        cp.parentCheckpointId(),
                        cp.originalGoal(),
                        cp.channelType(),
                        cp.channelPlatformId(),
                        cp.iterationsUsed(),
                        cp.maxIterations(),
                        cp.pendingToolName(),
                        cp.createdAt(),
                        cp.updatedAt()))
                .toList();
        return Response.ok(new ActiveAgentResponse(summaries.size(), summaries)).build();
    }

    @POST
    @Path("/{id}/run")
    @RunOnVirtualThread
    @Operation(summary = "Run an agent loop synchronously for a given goal")
    public Response runAgentSync(@PathParam("id") String sessionId, @Valid RunAgentRequest request) {
        String userId = resolveUserId();
        var result = sessionService.runSync(sessionId, userId, request);
        return toResponse(result);
    }

    @POST
    @Path("/{id}/run-async")
    @Operation(summary = "Start an agent loop asynchronously")
    public void runAgentAsync(
            @PathParam("id") String sessionId, @Valid RunAgentRequest request, @Suspended AsyncResponse asyncResponse) {
        String userId = resolveUserId();
        sessionService.runAsync(sessionId, userId, request, result -> {
            asyncResponse.resume(toResponse(result));
        });
    }

    @GET
    @Path("/{id}/status/{role}")
    @RunOnVirtualThread
    @Operation(summary = "Get the current reasoning status and history for a role in a session")
    public Response getSessionStatus(@PathParam("id") String sessionId, @PathParam("role") String role) {
        String userId = resolveUserId();
        var result = sessionService.getStatus(sessionId, userId, role);
        return toResponse(result);
    }

    @DELETE
    @Path("/{id}")
    @RunOnVirtualThread
    @Operation(summary = "Force terminate an active agent session")
    public Response terminateSession(@PathParam("id") String sessionId) {
        String userId = resolveUserId();
        var result = sessionService.terminateSession(sessionId, userId);
        if (!result.isSuccess()) {
            return toResponse(result);
        }
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/approve")
    @RunOnVirtualThread
    @Operation(summary = "Provide manual human approval for a pending tool execution")
    public Response approveSession(
            @PathParam("id") String sessionId,
            @QueryParam("agentRole") @DefaultValue(AgentRoleEnum.COORDINATOR_ID) String agentRole) {
        String userId = resolveUserId();
        var result = sessionService.approve(sessionId, userId, agentRole);
        return toResponse(result);
    }

    @POST
    @Path("/{id}/deny")
    @RunOnVirtualThread
    @Operation(summary = "Deny a pending tool execution and provide feedback to the agent")
    public Response denySession(
            @PathParam("id") String sessionId,
            @QueryParam("agentRole") @DefaultValue(AgentRoleEnum.COORDINATOR_ID) String agentRole) {
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
            case "SESSION_NOT_FOUND" ->
                Response.status(Response.Status.NOT_FOUND)
                        .entity(new AmshaErrorResponse(result.errorMessage()))
                        .build();
            case "SESSION_ACCESS_DENIED" ->
                Response.status(Response.Status.UNAUTHORIZED)
                        .entity(new AmshaErrorResponse(result.errorCode(), result.errorMessage(), null))
                        .build();
            case "UNKNOWN_ROLE", "FORK_BOMB_REJECTED" ->
                Response.status(Response.Status.BAD_REQUEST)
                        .entity(new AmshaErrorResponse(result.errorMessage()))
                        .build();
            case "AWAITING_APPROVAL" ->
                Response.status(Response.Status.CONFLICT)
                        .entity(new AmshaErrorResponse(result.errorMessage()))
                        .build();
            case "NO_PENDING_APPROVAL" ->
                Response.status(Response.Status.CONFLICT)
                        .entity(new AmshaErrorResponse(result.errorMessage()))
                        .build();
            case "BUDGET" ->
                Response.status(429)
                        .entity(new AmshaErrorResponse(result.errorMessage()))
                        .build();
            case "SYSTEM" ->
                Response.serverError()
                        .entity(new AmshaErrorResponse(result.errorMessage()))
                        .build();
            default ->
                Response.serverError()
                        .entity(new AmshaErrorResponse(result.errorMessage()))
                        .build();
        };
    }
}
