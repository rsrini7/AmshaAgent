package com.arcclaw.app.ui;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.app.ui.dto.PendingApprovalDto;
import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("hitl")
@RunOnVirtualThread
@Produces(MediaType.APPLICATION_JSON)
public class HitlResource {

    @Inject HitlService hitlService;

    @GET
    public List<PendingApprovalDto> listPending() {
        return hitlService.getPendingApprovals().values().stream()
            .map(pending -> new PendingApprovalDto(
                pending.taskId(),
                pending.toolName(),
                pending.params(),
                pending.requestedAt(),
                pending.agentRole()
            ))
            .collect(Collectors.toList());
    }

    @POST
    @Path("{taskId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response approve(@PathParam("taskId") String taskId) {
        try {
            hitlService.resolve(taskId, true, null);
            return Response.ok(Map.of("status", "approved", "taskId", taskId)).build();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("No pending")) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            if (e.getMessage() != null && e.getMessage().contains("already resolved")) {
                return Response.status(Response.Status.CONFLICT).build();
            }
            return Response.serverError().build();
        }
    }

    @POST
    @Path("{taskId}/reject")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response reject(@PathParam("taskId") String taskId, String reason) {
        String finalReason = (reason == null || reason.isBlank()) ? "Rejected by user" : reason;
        try {
            hitlService.resolve(taskId, false, finalReason);
            return Response.ok(Map.of("status", "rejected", "taskId", taskId)).build();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("No pending")) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.serverError().build();
        }
    }
}
