package com.amsha.app.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.amsha.app.ui.dto.CheckpointApprovalRequest;
import com.amsha.app.ui.dto.CheckpointApprovalResponse;
import com.amsha.app.ui.dto.PageDto;
import com.amsha.app.ui.dto.PendingApprovalDto;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestStreamElementType;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Multi;

@ApplicationScoped
@Path("hitl")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
@SuppressWarnings("java:S1192")
public class HitlResource {

    private static final String HITL_AUDIT_PREFIX = "hitl-";
    private static final String AUDIT_TASK_ID_PREFIX = "taskId=";
    private static final String COORDINATOR_ROLE = AgentRoleEnum.COORDINATOR.id();

    @Inject
    Instance<HitlService> hitlService;

    @Inject
    AgentSseService agentSseService;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    AuditService auditService;

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
            return Multi.createFrom().failure(new WebApplicationException(Response.Status.BAD_REQUEST));
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
    @Operation(summary = "List all pending tool execution approvals for the current user")
    public Response listPending(
            @QueryParam("page") @DefaultValue("0") int page, @QueryParam("size") @DefaultValue("20") int size) {
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

        if (!hitlService.isUnsatisfied()) {
            long totalCount = hitlService.get().countPendingApprovals(userId);
            List<PendingApprovalDto> items =
                    hitlService.get().getPendingApprovals(userId, effectiveSize, offset).values().stream()
                            .map(pending -> new PendingApprovalDto(
                                    pending.taskId(),
                                    pending.sessionId(),
                                    pending.toolName(),
                                    pending.params(),
                                    pending.requestedAt(),
                                    pending.agentRole()))
                            .collect(Collectors.toList());
            PageDto<PendingApprovalDto> pageResult = PageDto.of(items, page, effectiveSize, totalCount);
            return Response.ok(pageResult).build();
        }
        // No database backend — HITL not available
        return Response.ok(PageDto.of(List.of(), page, effectiveSize, 0)).build();
    }

    @POST
    @Path("{taskId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
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
        if (hitlService.isUnsatisfied()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED)
                    .entity(new AmshaErrorResponse("HITL not available", "No database backend"))
                    .build();
        }
        try {
            hitlService.get().resolve(taskId, toolName, true, reason);
            String userId = (securityContext != null && securityContext.getUserPrincipal() != null)
                    ? securityContext.getUserPrincipal().getName()
                    : "anonymous";
            auditService.recordMandatory(
                    HITL_AUDIT_PREFIX + taskId,
                    userId,
                    COORDINATOR_ROLE,
                    SecurityEventType.APPROVAL_GRANTED,
                    AUDIT_TASK_ID_PREFIX + taskId + " tool=" + toolName + " via=REST");
            Response response = Response.ok(new CheckpointApprovalResponse("approved", taskId, toolName, reason))
                    .build();
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
        String reason = (request != null
                        && request.reason() != null
                        && !request.reason().isBlank())
                ? request.reason()
                : "Rejected by user";
        if (hitlService.isUnsatisfied()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED)
                    .entity(new AmshaErrorResponse("HITL not available", "No database backend"))
                    .build();
        }
        try {
            hitlService.get().resolve(taskId, toolName, false, reason);
            String userId = (securityContext != null && securityContext.getUserPrincipal() != null)
                    ? securityContext.getUserPrincipal().getName()
                    : "anonymous";
            auditService.recordMandatory(
                    HITL_AUDIT_PREFIX + taskId,
                    userId,
                    COORDINATOR_ROLE,
                    SecurityEventType.APPROVAL_DENIED,
                    AUDIT_TASK_ID_PREFIX + taskId + " tool=" + toolName + " via=REST");
            Response response = Response.ok(new CheckpointApprovalResponse("rejected", taskId, toolName, reason))
                    .build();
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
                    .entity(new AmshaErrorResponse("No pending approval found", "taskId: " + taskId))
                    .build();
        }
        if (e.getMessage() != null && e.getMessage().contains("already resolved")) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new AmshaErrorResponse("Already resolved", "taskId: " + taskId))
                    .build();
        }
        return Response.serverError()
                .entity(new AmshaErrorResponse(
                        "System Error", e.getMessage() != null ? e.getMessage() : "Unknown error"))
                .build();
    }
}
