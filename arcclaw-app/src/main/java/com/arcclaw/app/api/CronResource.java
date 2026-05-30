package com.arcclaw.app.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;
import java.util.Map;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.api.dto.ArcClawErrorResponse;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronRunDto;
import com.arcclaw.persistence.api.CronRunStorePort;
import com.arcclaw.persistence.api.CronStorePort;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/cron")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class CronResource {

    private static final Logger log = Logger.getLogger(CronResource.class);
    private static final String CRON_AUDIT_PREFIX = "cron-";
    private static final String AUDIT_CRON_ID_PREFIX = "cronId=";
    private static final String COORDINATOR_ROLE = AgentRoleEnum.COORDINATOR.id();

    @Inject
    CronStorePort cronStore;

    @Inject
    CronRunStorePort cronRunStore;

    @Inject
    AuditService auditService;

    @Context
    SecurityContext securityContext;

    @GET
    @Operation(summary = "List cron jobs for current user with pagination and optional status filter")
    public Response listCrons(
            @QueryParam("status") String status,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {

        String userId = resolveUserId();
        int limit = Math.min(200, Math.max(1, size));
        int offset = Math.max(0, page) * limit;

        List<AgentCronDto> crons = cronStore.findByUserId(userId, status, offset, limit);
        long total = cronStore.countByUserId(userId, status);

        return Response.ok(Map.of(
                        "crons", crons,
                        "total", total,
                        "page", page,
                        "size", limit))
                .build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a single cron job by ID")
    public Response getCron(@PathParam("id") String id) {
        return cronStore
                .findById(id)
                .map(cron -> Response.ok(cron).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(new ArcClawErrorResponse("Cron job not found: " + id))
                        .build());
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete a cron job by ID")
    public Response deleteCron(@PathParam("id") String id) {
        String userId = resolveUserId();
        int deleted = cronStore.deleteByIdAndUserId(id, userId);
        if (deleted == 0) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ArcClawErrorResponse("Cron job not found or not owned by user: " + id))
                    .build();
        }
        log.infof("Deleted cron job %s by user %s", id, userId);
        auditService.recordMandatory(
                CRON_AUDIT_PREFIX + id,
                userId,
                COORDINATOR_ROLE,
                SecurityEventType.CRONDELETED,
                AUDIT_CRON_ID_PREFIX + id + " via=REST");
        return Response.ok(Map.of("deleted", true, "id", id)).build();
    }

    @POST
    @Path("/{id}/pause")
    @Operation(summary = "Pause a cron job (set status to COMPLETED to stop firing)")
    public Response pauseCron(@PathParam("id") String id) {
        String userId = resolveUserId();
        return cronStore
                .findById(id)
                .map(cron -> {
                    if (!userId.equals(cron.userId())) {
                        return Response.status(Response.Status.FORBIDDEN)
                                .entity(new ArcClawErrorResponse("Not authorized to pause this cron job"))
                                .build();
                    }
                    if (!"ACTIVE".equals(cron.status()) && !"RUNNING".equals(cron.status())) {
                        return Response.status(Response.Status.CONFLICT)
                                .entity(new ArcClawErrorResponse("Cannot pause cron in status: " + cron.status()))
                                .build();
                    }
                    cronStore.markCompleted(cron.id(), cron.lastFireTime());
                    log.infof("Paused cron job %s", id);
                    auditService.recordMandatory(
                            CRON_AUDIT_PREFIX + id,
                            userId,
                            COORDINATOR_ROLE,
                            SecurityEventType.CRON_PAUSED,
                            AUDIT_CRON_ID_PREFIX + id);
                    return Response.ok(Map.of("paused", true, "id", id)).build();
                })
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(new ArcClawErrorResponse("Cron job not found: " + id))
                        .build());
    }

    @POST
    @Path("/{id}/resume")
    @Operation(summary = "Resume a paused cron job (set status back to ACTIVE)")
    public Response resumeCron(@PathParam("id") String id) {
        String userId = resolveUserId();
        return cronStore
                .findById(id)
                .map(cron -> {
                    if (!userId.equals(cron.userId())) {
                        return Response.status(Response.Status.FORBIDDEN)
                                .entity(new ArcClawErrorResponse("Not authorized to resume this cron job"))
                                .build();
                    }
                    if (!"COMPLETED".equals(cron.status()) && !"FAILED".equals(cron.status())) {
                        return Response.status(Response.Status.CONFLICT)
                                .entity(new ArcClawErrorResponse("Cannot resume cron in status: " + cron.status()))
                                .build();
                    }
                    AgentCronDto reactivated = new AgentCronDto(
                            cron.id(),
                            cron.sessionId(),
                            cron.userId(),
                            cron.chatId(),
                            cron.channelType(),
                            cron.channelPlatformId(),
                            cron.cronExpression(),
                            cron.timezone(),
                            cron.runOnce(),
                            cron.goal(),
                            cron.nextFireTime(),
                            cron.lastFireTime(),
                            "ACTIVE",
                            cron.executionRole(),
                            null,
                            cron.lastDeliveryId(),
                            cron.createdAt(),
                            cron.updatedAt());
                    cronStore.save(reactivated);
                    log.infof("Resumed cron job %s", id);
                    auditService.recordMandatory(
                            CRON_AUDIT_PREFIX + id,
                            userId,
                            COORDINATOR_ROLE,
                            SecurityEventType.CRON_RESUMED,
                            AUDIT_CRON_ID_PREFIX + id);
                    return Response.ok(Map.of("resumed", true, "id", id)).build();
                })
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(new ArcClawErrorResponse("Cron job not found: " + id))
                        .build());
    }

    @POST
    @Path("/{id}/trigger")
    @Operation(summary = "Force-fire a cron job immediately")
    public Response triggerCron(@PathParam("id") String id) {
        String userId = resolveUserId();
        return cronStore
                .findById(id)
                .map(cron -> {
                    if (!userId.equals(cron.userId())) {
                        return Response.status(Response.Status.FORBIDDEN)
                                .entity(new ArcClawErrorResponse("Not authorized to trigger this cron job"))
                                .build();
                    }
                    boolean claimed = cronStore.claimForFire(id);
                    if (!claimed) {
                        return Response.status(Response.Status.CONFLICT)
                                .entity(new ArcClawErrorResponse("Cannot trigger cron in status: " + cron.status()))
                                .build();
                    }
                    cronStore.releaseClaimToActive(id);
                    log.infof("Force-triggered cron job %s", id);
                    auditService.recordMandatory(
                            CRON_AUDIT_PREFIX + id,
                            userId,
                            COORDINATOR_ROLE,
                            SecurityEventType.CRON_FORCE_TRIGGERED,
                            AUDIT_CRON_ID_PREFIX + id);
                    return Response.ok(Map.of("triggered", true, "id", id)).build();
                })
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(new ArcClawErrorResponse("Cron job not found: " + id))
                        .build());
    }

    @GET
    @Path("/{id}/runs")
    @Operation(summary = "Get execution history for a cron job")
    public Response getCronRuns(
            @PathParam("id") String id,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        String userId = resolveUserId();
        return cronStore
                .findById(id)
                .map(cron -> {
                    if (!userId.equals(cron.userId())) {
                        return Response.status(Response.Status.FORBIDDEN)
                                .entity(new ArcClawErrorResponse("Not authorized to view this cron job"))
                                .build();
                    }
                    int limit = Math.min(100, Math.max(1, size));
                    int offset = Math.max(0, page) * limit;
                    List<CronRunDto> runs = cronRunStore.findByCronId(id, offset, limit);
                    long total = cronRunStore.countByCronId(id);
                    return Response.ok(Map.of("runs", runs, "total", total, "page", page, "size", limit))
                            .build();
                })
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(new ArcClawErrorResponse("Cron job not found: " + id))
                        .build());
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }
}
