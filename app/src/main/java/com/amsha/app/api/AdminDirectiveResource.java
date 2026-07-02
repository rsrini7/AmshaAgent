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

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.policy.InteractionPolicySynthesizer;
import com.amsha.selfevolve.policy.ShadowLifecycleManager;
import com.amsha.selfevolve.store.DirectiveStorePort;

import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * Admin REST resource for managing synthesized behavioral directives.
 * <p>
 * Provides endpoints to list, view, disable, and delete directives.
 * Write endpoints (DELETE, POST) require internal API key.
 */
@Path("/admin/memory/directives")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class AdminDirectiveResource {

    @Inject
    DirectiveStorePort directiveStore;

    @Inject
    InteractionPolicySynthesizer synthesizer;

    @Inject
    ShadowLifecycleManager lifecycleManager;

    @Context
    SecurityContext securityContext;

    /**
     * Lists all directives for the current user.
     */
    @GET
    public Response listDirectives(@QueryParam("limit") Integer limit) {
        String userId = resolveUserId();
        int effectiveLimit = limit != null ? limit : 20;
        List<SynthesizedDirective> directives = directiveStore.getAll(userId, effectiveLimit);
        return Response.ok(directives).build();
    }

    /**
     * Gets a specific directive by ID.
     */
    @GET
    @Path("/{directiveId}")
    public Response getDirective(@PathParam("directiveId") String directiveId) {
        SynthesizedDirective directive = directiveStore.getById(directiveId);
        if (directive == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new AmshaErrorResponse("Directive not found: " + directiveId))
                    .build();
        }
        return Response.ok(directive).build();
    }

    /**
     * Disables a directive (admin action).
     */
    @POST
    @Path("/{directiveId}/disable")
    public Response disableDirective(@PathParam("directiveId") String directiveId) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can disable directives"))
                    .build();
        }
        SynthesizedDirective directive = directiveStore.getById(directiveId);
        if (directive == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new AmshaErrorResponse("Directive not found: " + directiveId))
                    .build();
        }
        if (directive.state() == DirectiveState.DISABLED) {
            return Response.ok(directive).build();
        }
        SynthesizedDirective disabled = lifecycleManager.disable(directive);
        return Response.ok(disabled).build();
    }

    /**
     * Deletes a directive (admin action).
     */
    @DELETE
    @Path("/{directiveId}")
    public Response deleteDirective(@PathParam("directiveId") String directiveId) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can delete directives"))
                    .build();
        }
        boolean deleted = directiveStore.delete(directiveId);
        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new AmshaErrorResponse("Directive not found: " + directiveId))
                    .build();
        }
        return Response.noContent().build();
    }

    /**
     * Triggers directive synthesis for the current user.
     */
    @POST
    @Path("/synthesize")
    public Response synthesizeDirectives() {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can trigger synthesis"))
                    .build();
        }
        String userId = resolveUserId();
        List<SynthesizedDirective> newDirectives = synthesizer.synthesize(userId);
        return Response.ok(newDirectives).build();
    }

    /**
     * Reconciles all SHADOW directives (promote/disable based on evaluation).
     */
    @POST
    @Path("/reconcile")
    public Response reconcileDirectives() {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can reconcile directives"))
                    .build();
        }
        int actions = lifecycleManager.reconcileAll();
        return Response.ok(new ReconcileResponse(actions)).build();
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

    public record ReconcileResponse(int actionsTaken) {}
}
