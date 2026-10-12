package com.amsha.app.api;

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

import java.util.List;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.memory.tiered.CoreMemoryService;
import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.CoreMemoryBlockDto;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/admin/memory/core")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
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
        return coreMemoryService
                .get(userId, blockKey)
                .map(Response::ok)
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(new AmshaErrorResponse("Core memory block not found: " + blockKey)))
                .build();
    }

    @PUT
    @Path("/{blockKey}")
    public Response upsertBlock(@PathParam("blockKey") String blockKey, CoreMemoryUpdateRequest req) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can modify core memory"))
                    .build();
        }
        String userId = resolveUserId();
        try {
            coreMemoryService.upsert(userId, blockKey, req.content());
            return Response.ok().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("invalid_block_key", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("limit reached")) {
                return Response.status(429)
                        .entity(new AmshaErrorResponse("block_limit_exceeded", e.getMessage()))
                        .build();
            }
            return Response.status(Response.Status.CONFLICT)
                    .entity(new AmshaErrorResponse("version_conflict", e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{blockKey}")
    public Response deleteBlock(@PathParam("blockKey") String blockKey) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can delete core memory"))
                    .build();
        }
        String userId = resolveUserId();
        coreMemoryService.delete(userId, blockKey);
        return Response.noContent().build();
    }

    private boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole(AccessRoles.INTERNAL);
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    public record CoreMemoryUpdateRequest(String content) {}
}
