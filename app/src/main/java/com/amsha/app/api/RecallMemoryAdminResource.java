package com.amsha.app.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;
import java.util.Set;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.memory.tiered.RecallMemoryService;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;

import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * Admin REST resource for Recall Memory operations.
 * <p>
 * Provides endpoints to search recall memory and manage retention.
 * Write endpoints (POST) require internal API key.
 */
@Path("/admin/memory/recall")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class RecallMemoryAdminResource {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    @Inject
    RecallMemoryService recallService;

    @Context
    SecurityContext securityContext;

    /**
     * Search recall memory with optional filters.
     */
    @GET
    @Path("/search")
    public Response search(
            @QueryParam("query") String query,
            @QueryParam("sessionId") String sessionId,
            @QueryParam("limit") Integer limit,
            @QueryParam("includeSummaries") Boolean includeSummaries) {

        String userId = resolveUserId();
        int effectiveLimit = limit != null ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        boolean effectiveIncludeSummaries = includeSummaries != null && includeSummaries;

        RecallSearchQuery searchQuery = new RecallSearchQuery(
                userId, query != null ? query : "", sessionId, effectiveLimit, effectiveIncludeSummaries, Set.of());

        List<RecallMessageDto> results = recallService.search(searchQuery);
        return Response.ok(results).build();
    }

    /**
     * Purge recall messages older than retentionDays for the current user.
     * Requires internal API key.
     */
    @POST
    @Path("/purge")
    public Response purgeExpired(@QueryParam("retentionDays") Integer retentionDays) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can purge recall memory"))
                    .build();
        }
        String userId = resolveUserId();
        int effectiveRetentionDays = retentionDays != null ? retentionDays : 30;

        try {
            recallService.purgeExpired(userId, effectiveRetentionDays);
            return Response.ok(new PurgeResponse(userId, effectiveRetentionDays))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(new AmshaErrorResponse("purge_failed", e.getMessage()))
                    .build();
        }
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

    public record PurgeResponse(String userId, int retentionDays) {}
}
