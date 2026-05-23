package com.arcclaw.app.ui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.arcclaw.app.ui.dto.CheckpointApprovalRequest;
import com.arcclaw.app.ui.dto.CheckpointApprovalResponse;
import com.arcclaw.app.ui.dto.PageDto;
import com.arcclaw.app.ui.dto.PendingApprovalDto;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.api.dto.ArcClawErrorResponse;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Multi;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestStreamElementType;

@ApplicationScoped
@IfBuildProperty(name = "quarkus.flyway.enabled", stringValue = "true", enableIfMissing = true)
@Path("hitl")
@Produces(MediaType.APPLICATION_JSON)
public class HitlResource {

    @Inject HitlService hitlService;
    @Inject AgentSseService agentSseService;
    @Inject SessionHeartbeatTracker heartbeatTracker;
    @Inject AuditService auditService;

    @Context
    SecurityContext securityContext;

    // Idempotency cache: idempotencyKey → Response (prevents double-approvals via X-Idempotency-Key)
    private final Cache<String, Response> idempotencyCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(summary = "Subscribe to live HITL approval requests via SSE")
    public Multi<AgentSseService.SseEvent> hitlEvents(@QueryParam("sessionId") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Multi.createFrom().failure(
                new WebApplicationException(Response.Status.BAD_REQUEST));
        }

        // §85-B: Lazy ownership binding for SSE subscription.
        // ApiKeyAuthFilter has already validated the key and put userId in MDC.
        String userId = (securityContext != null && securityContext.getUserPrincipal() != null)
            ? securityContext.getUserPrincipal().getName()
            : "anonymous";
        if (userId == null || "anonymous".equals(userId)) {
            return Multi.createFrom().failure(new WebApplicationException(Response.Status.UNAUTHORIZED));
        }
        
        if (!heartbeatTracker.tryBindOwner(sessionId, userId)) {
            // Either session reaped, or owned by someone else.
            return Multi.createFrom().failure(new WebApplicationException(Response.Status.UNAUTHORIZED));
        }

        return agentSseService.subscribe(sessionId);
    }

    @GET
    @RunOnVirtualThread
    @Operation(summary = "List all pending tool execution approvals for the current user")
    public Response listPending(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        // ApiKeyAuthFilter has already validated the key and put userId in MDC.
        String userId = (securityContext != null && securityContext.getUserPrincipal() != null)
            ? securityContext.getUserPrincipal().getName()
            : "anonymous";
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Cannot resolve user identity"))
                    .build();
        }

        int effectiveSize = Math.min(Math.max(1, size), 50);
        int offset = page * effectiveSize;

        long totalCount = hitlService.countPendingApprovals(userId);
        List<PendingApprovalDto> items = hitlService.getPendingApprovals(userId, effectiveSize, offset)
            .values().stream()
            .map(pending -> new PendingApprovalDto(
                pending.taskId(),
                pending.sessionId(),
                pending.toolName(),
                pending.params(),
                pending.requestedAt(),
                pending.agentRole()
            ))
            .collect(Collectors.toList());

        PageDto<PendingApprovalDto> pageResult = PageDto.of(items, page, effectiveSize, totalCount);
        return Response.ok(pageResult).build();
    }

    @POST
    @Path("{taskId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(summary = "Approve a pending tool execution")
    public Response approve(
            @PathParam("taskId") String taskId,
            CheckpointApprovalRequest request,
            @HeaderParam("X-Idempotency-Key") String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Response cached = idempotencyCache.getIfPresent(idempotencyKey);
            if (cached != null) return cached;
        }
        String toolName = (request != null) ? request.toolName() : null;
        String reason = (request != null) ? request.reason() : "Approved via REST";
        try {
            hitlService.resolve(taskId, toolName, true, reason);
            String userId = (securityContext != null && securityContext.getUserPrincipal() != null)
                ? securityContext.getUserPrincipal().getName() : "anonymous";
            auditService.recordMandatory("hitl-" + taskId, userId, "coordinator",
                    SecurityEventType.APPROVAL_GRANTED,
                    "taskId=" + taskId + " tool=" + toolName + " via=REST");
            Response response = Response.ok(new CheckpointApprovalResponse("approved", taskId, toolName, reason)).build();
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyCache.put(idempotencyKey, response);
            }
            return response;
        } catch (Exception e) {
            return handleException(taskId, e);
        }
    }

    @POST
    @Path("{taskId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(summary = "Reject a pending tool execution")
    public Response reject(
            @PathParam("taskId") String taskId,
            CheckpointApprovalRequest request,
            @HeaderParam("X-Idempotency-Key") String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Response cached = idempotencyCache.getIfPresent(idempotencyKey);
            if (cached != null) return cached;
        }
        String toolName = (request != null) ? request.toolName() : null;
        String reason = (request != null && request.reason() != null && !request.reason().isBlank()) 
            ? request.reason() : "Rejected by user";
        try {
            hitlService.resolve(taskId, toolName, false, reason);
            String userId = (securityContext != null && securityContext.getUserPrincipal() != null)
                ? securityContext.getUserPrincipal().getName() : "anonymous";
            auditService.recordMandatory("hitl-" + taskId, userId, "coordinator",
                    SecurityEventType.APPROVAL_DENIED,
                    "taskId=" + taskId + " tool=" + toolName + " via=REST");
            Response response = Response.ok(new CheckpointApprovalResponse("rejected", taskId, toolName, reason)).build();
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyCache.put(idempotencyKey, response);
            }
            return response;
        } catch (Exception e) {
            return handleException(taskId, e);
        }
    }

    private Response handleException(String taskId, Exception e) {
        if (e.getMessage() != null && e.getMessage().contains("No pending")) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ArcClawErrorResponse("No pending approval found", "taskId: " + taskId))
                .build();
        }
        if (e.getMessage() != null && e.getMessage().contains("already resolved")) {
            return Response.status(Response.Status.CONFLICT)
                .entity(new ArcClawErrorResponse("Already resolved", "taskId: " + taskId))
                .build();
        }
        return Response.serverError()
            .entity(new ArcClawErrorResponse("System Error", e.getMessage() != null ? e.getMessage() : "Unknown error"))
            .build();
    }
}
