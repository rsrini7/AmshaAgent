package com.arcclaw.app.ui;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.app.ui.dto.PendingApprovalDto;
import io.smallrye.common.annotation.RunOnVirtualThread;

@ApplicationScoped
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
    // No @Consumes — approve takes no request body, taskId is in the path only
    public Response approve(@PathParam("taskId") String taskId) {
        try {
            hitlService.resolve(taskId, true, null);
            return Response.ok(Map.of("status", "approved", "taskId", taskId)).build();
        } catch (Exception e) {
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

    @POST
    @Path("{taskId}/reject")
    // reject genuinely consumes a plain-text reason body
    public Response reject(@PathParam("taskId") String taskId, String reason) {
        String finalReason = (reason == null || reason.isBlank()) ? "Rejected by user" : reason;
        try {
            hitlService.resolve(taskId, false, finalReason);
            return Response.ok(Map.of("status", "rejected", "taskId", taskId,
                "reason", finalReason)).build();
        } catch (Exception e) {
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
}
