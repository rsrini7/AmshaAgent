package com.amsha.app.connector.github;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.io.InputStream;
import java.security.Principal;
import java.util.Optional;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import io.smallrye.common.annotation.RunOnVirtualThread;

/** Internal owner-only API for the selected GitHub README connector. */
@Path("/connectors/github-readme")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class GitHubReadmeConnectorResource {

    private final GitHubReadmeConnectorService connectorService;
    private final IdentityStorePort identityStore;
    private final ActiveWorkspaceContext activeWorkspaceContext;

    @Context
    SecurityContext securityContext;

    @Inject
    public GitHubReadmeConnectorResource(
            GitHubReadmeConnectorService connectorService,
            IdentityStorePort identityStore,
            ActiveWorkspaceContext activeWorkspaceContext) {
        this.connectorService = connectorService;
        this.identityStore = identityStore;
        this.activeWorkspaceContext = activeWorkspaceContext;
    }

    @POST
    @Path("connect")
    @Consumes(MediaType.WILDCARD)
    public Response connect(InputStream requestBody) {
        Response bodyError = rejectBody(requestBody);
        return bodyError != null ? bodyError : execute(Operation.CONNECT);
    }

    @POST
    @Path("refresh")
    @Consumes(MediaType.WILDCARD)
    public Response refresh(InputStream requestBody) {
        Response bodyError = rejectBody(requestBody);
        return bodyError != null ? bodyError : execute(Operation.REFRESH);
    }

    @GET
    @Path("status")
    public Response status() {
        Optional<OwnerWorkspace> scope = resolveOwnerWorkspace();
        if (scope.isEmpty()) {
            return authorizationFailure();
        }
        OwnerWorkspace selected = scope.get();
        return Response.ok(connectorService.status(selected.ownerUserId(), selected.workspaceId()))
                .build();
    }

    @DELETE
    public Response disconnect() {
        Optional<OwnerWorkspace> scope = resolveOwnerWorkspace();
        if (scope.isEmpty()) {
            return authorizationFailure();
        }
        OwnerWorkspace selected = scope.get();
        try {
            return Response.ok(connectorService.disconnect(selected.ownerUserId(), selected.workspaceId()))
                    .build();
        } catch (GitHubReadmeConnectorException e) {
            return connectorFailure(e.kind());
        }
    }

    private Response execute(Operation operation) {
        Optional<OwnerWorkspace> scope = resolveOwnerWorkspace();
        if (scope.isEmpty()) {
            return authorizationFailure();
        }
        OwnerWorkspace selected = scope.get();
        try {
            GitHubReadmeStatusResponse result =
                    switch (operation) {
                        case CONNECT -> connectorService.connect(selected.ownerUserId(), selected.workspaceId());
                        case REFRESH -> connectorService.refresh(selected.ownerUserId(), selected.workspaceId());
                    };
            return Response.ok(result).build();
        } catch (GitHubReadmeConnectorException e) {
            return connectorFailure(e.kind());
        }
    }

    private Optional<OwnerWorkspace> resolveOwnerWorkspace() {
        if (securityContext == null || !securityContext.isUserInRole(AccessRoles.INTERNAL)) {
            return Optional.empty();
        }
        Principal principal = securityContext.getUserPrincipal();
        Optional<String> workspace = activeWorkspaceContext.workspaceId();
        if (principal == null
                || principal.getName() == null
                || principal.getName().isBlank()
                || workspace.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<WorkspaceMembershipDto> membership =
                    identityStore.findMembership(workspace.get(), principal.getName());
            if (membership.isEmpty()
                    || !"ACTIVE".equalsIgnoreCase(membership.get().status())
                    || !"OWNER".equalsIgnoreCase(membership.get().role())) {
                return Optional.empty();
            }
            return Optional.of(new OwnerWorkspace(principal.getName(), workspace.get()));
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    private Response rejectBody(InputStream requestBody) {
        if (requestBody == null) {
            return null;
        }
        try {
            if (requestBody.read() == -1) {
                return null;
            }
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse(
                            "unexpected_body", "This connector operation does not accept a request body"))
                    .build();
        } catch (IOException _) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("invalid_request", "The connector request could not be read"))
                    .build();
        }
    }

    private Response authorizationFailure() {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new AmshaErrorResponse("unauthorized", "Internal owner access is required"))
                    .build();
        }
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new AmshaErrorResponse("forbidden", "Internal owner access is required"))
                .build();
    }

    private static Response connectorFailure(GitHubReadmeConnectorException.Kind kind) {
        return switch (kind) {
            case NOT_CONFIGURED ->
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(new AmshaErrorResponse(
                                "connector_not_configured", "GitHub App credentials are not configured"))
                        .build();
            case SOURCE_UNAVAILABLE ->
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(new AmshaErrorResponse(
                                "source_unavailable", "GitHub README refresh is temporarily unavailable"))
                        .build();
            case INVALID_SOURCE_RESPONSE ->
                Response.status(Response.Status.BAD_GATEWAY)
                        .entity(new AmshaErrorResponse(
                                "invalid_source_response", "GitHub returned an invalid connector response"))
                        .build();
            case INDEX_UNAVAILABLE ->
                Response.serverError()
                        .entity(new AmshaErrorResponse("index_unavailable", "GitHub README index could not be updated"))
                        .build();
        };
    }

    private enum Operation {
        CONNECT,
        REFRESH
    }

    private record OwnerWorkspace(String ownerUserId, String workspaceId) {}
}
