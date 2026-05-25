package com.arcclaw.app.api;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.arcclaw.core.api.dto.ArcClawErrorResponse;
import com.arcclaw.core.memory.tiered.CoreMemoryService;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.persistence.api.CoreMemoryBlockDto;

@Path("/admin/memory/core")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CoreMemoryAdminResource {

    @Inject
    CoreMemoryService coreMemoryService;

    @Context
    SecurityContext securityContext;

    @GET
    public Response listBlocks() {
        String userId = resolveUserId();
        List<CoreMemoryBlockDto> blocks = coreMemoryService.readAll(userId);
        return Response.ok(blocks).build();
    }

    @GET
    @Path("/{blockKey}")
    public Response getBlock(@PathParam("blockKey") String blockKey) {
        String userId = resolveUserId();
        return coreMemoryService.get(userId, blockKey)
            .map(Response::ok)
            .orElse(Response.status(Response.Status.NOT_FOUND))
            .build();
    }

    @PUT
    @Path("/{blockKey}")
    public Response upsertBlock(@PathParam("blockKey") String blockKey, CoreMemoryUpdateRequest req) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity(new ArcClawErrorResponse("Only internal API keys can modify core memory"))
                .build();
        }
        String userId = resolveUserId();
        try {
            coreMemoryService.upsert(userId, blockKey, req.content());
            return Response.ok().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ArcClawErrorResponse("invalid_block_key", e.getMessage()))
                .build();
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("limit reached")) {
                return Response.status(429)
                    .entity(new ArcClawErrorResponse("block_limit_exceeded", e.getMessage()))
                    .build();
            }
            return Response.status(Response.Status.CONFLICT)
                .entity(new ArcClawErrorResponse("version_conflict", e.getMessage()))
                .build();
        }
    }

    @DELETE
    @Path("/{blockKey}")
    public Response deleteBlock(@PathParam("blockKey") String blockKey) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity(new ArcClawErrorResponse("Only internal API keys can delete core memory"))
                .build();
        }
        String userId = resolveUserId();
        coreMemoryService.delete(userId, blockKey);
        return Response.noContent().build();
    }

    private boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole("internal");
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    public record CoreMemoryUpdateRequest(String content) {}
}
