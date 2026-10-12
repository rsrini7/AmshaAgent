package com.amsha.app.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import java.util.Optional;
import java.util.Set;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.memory.tiered.ArchivalMemoryService;
import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalSearchQuery;

import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * Admin REST resource for Archival Memory operations.
 * <p>
 * Provides endpoints to search, view, and manage archival memory chunks.
 * Write endpoints (DELETE, POST) require internal API key.
 */
@Path("/admin/memory/archival")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ArchivalMemoryAdminResource {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    @Inject
    ArchivalMemoryService archivalService;

    @Context
    SecurityContext securityContext;

    /**
     * Search archival memory with optional filters.
     */
    @GET
    @Path("/search")
    public Response search(
            @QueryParam("query") String query,
            @QueryParam("limit") Integer limit,
            @QueryParam("chunkTypes") String chunkTypes) {

        String userId = resolveUserId();
        int effectiveLimit = limit != null ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        Set<String> effectiveChunkTypes = parseChunkTypes(chunkTypes);

        ArchivalSearchQuery searchQuery = new ArchivalSearchQuery(
                userId, query != null ? query : "", effectiveLimit, effectiveChunkTypes, false, true, null);

        List<ArchivalChunkDto> results = archivalService.search(searchQuery);
        return Response.ok(results).build();
    }

    /**
     * Get a specific archival chunk by ID.
     */
    @GET
    @Path("/{chunkId}")
    public Response getById(@PathParam("chunkId") String chunkId) {
        Optional<ArchivalChunkDto> chunk = archivalService.getById(chunkId);
        return chunk.map(Response::ok)
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(new AmshaErrorResponse("Archival chunk not found: " + chunkId)))
                .build();
    }

    /**
     * Delete a specific archival chunk by ID.
     * Requires internal API key.
     */
    @DELETE
    @Path("/{chunkId}")
    public Response delete(@PathParam("chunkId") String chunkId) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can delete archival memory"))
                    .build();
        }
        archivalService.delete(chunkId);
        return Response.noContent().build();
    }

    /**
     * Evict low-confidence archival chunks.
     * Requires internal API key.
     */
    @POST
    @Path("/evict")
    public Response evictLowConfidence(
            @QueryParam("threshold") Double threshold, @QueryParam("minAgeDays") Integer minAgeDays) {

        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can evict archival memory"))
                    .build();
        }

        double effectiveThreshold = threshold != null ? threshold : 0.5;
        int effectiveMinAgeDays = minAgeDays != null ? minAgeDays : 30;

        try {
            archivalService.evictLowConfidence(effectiveThreshold, effectiveMinAgeDays);
            return Response.ok(new EvictResponse(effectiveThreshold, effectiveMinAgeDays))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(new AmshaErrorResponse("evict_failed", e.getMessage()))
                    .build();
        }
    }

    private Set<String> parseChunkTypes(String chunkTypes) {
        if (chunkTypes == null || chunkTypes.isBlank()) {
            return Set.of();
        }
        return Set.of(chunkTypes.split(","));
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

    public record EvictResponse(double threshold, int minAgeDays) {}
}
