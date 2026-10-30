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
import java.util.List;
import java.util.Optional;

import com.amsha.common.config.ConfigKeys;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import io.smallrye.common.annotation.RunOnVirtualThread;

/** Internal owner-only API for the per-connection GitHub documentation source. */
@Path("/connectors/github-readme")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class GitHubReadmeConnectorResource {
    private final GitHubReadmeConnectorService connectorService;
    private final GitHubReadmeConnectionSettingsParser settingsParser;
    private final IdentityStorePort identityStore;
    private final ActiveWorkspaceContext activeWorkspaceContext;

    @Context
    SecurityContext securityContext;

    @Inject
    public GitHubReadmeConnectorResource(
            GitHubReadmeConnectorService connectorService,
            GitHubReadmeConnectionSettingsParser settingsParser,
            IdentityStorePort identityStore,
            ActiveWorkspaceContext activeWorkspaceContext) {
        this.connectorService = connectorService;
        this.settingsParser = settingsParser;
        this.identityStore = identityStore;
        this.activeWorkspaceContext = activeWorkspaceContext;
    }

    @GET
    @Path("workspaces")
    public Response listOwnerWorkspaces() {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            return authorizationFailure();
        }
        if (!securityContext.isUserInRole(AccessRoles.INTERNAL)) {
            return authorizationFailure();
        }
        return Response.ok(activeOwnerMemberships(
                        securityContext.getUserPrincipal().getName()))
                .build();
    }

    @POST
    @Path("connect")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response connect(InputStream requestBody) {
        Optional<OwnerWorkspace> scope = resolveOwnerWorkspace();
        if (scope.isEmpty()) {
            return authorizationFailure();
        }
        GitHubReadmeConnectionSettings settings;
        try {
            settings = settingsParser.parse(requestBody);
        } catch (IllegalArgumentException _) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse(
                            "invalid_connector_settings", "GitHub connection settings are invalid"))
                    .build();
        }
        try {
            OwnerWorkspace selected = scope.get();
            return Response.ok(connectorService.connect(selected.ownerUserId(), selected.workspaceId(), settings))
                    .build();
        } catch (GitHubReadmeConnectorException e) {
            return connectorFailure(e.kind());
        }
    }

    @POST
    @Path("refresh")
    @Consumes(MediaType.WILDCARD)
    public Response refresh(InputStream requestBody) {
        Response bodyError = rejectUnexpectedBody(requestBody);
        return bodyError != null ? bodyError : executeRefresh();
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

    private Response executeRefresh() {
        Optional<OwnerWorkspace> scope = resolveOwnerWorkspace();
        if (scope.isEmpty()) {
            return authorizationFailure();
        }
        OwnerWorkspace selected = scope.get();
        try {
            return Response.ok(connectorService.refresh(selected.ownerUserId(), selected.workspaceId()))
                    .build();
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
            if (AccessRoles.INTERNAL.equalsIgnoreCase(principal.getName())) {
                return identityStore.listMembershipsByWorkspaceId(workspace.get()).stream()
                        .filter(GitHubReadmeConnectorResource::isActiveOwner)
                        .map(membership -> new OwnerWorkspace(membership.userId(), workspace.get()))
                        .findFirst();
            }
            Optional<WorkspaceMembershipDto> membership =
                    identityStore.findMembership(workspace.get(), principal.getName());
            if (membership.isEmpty() || !isActiveOwner(membership.get())) {
                return Optional.empty();
            }
            return Optional.of(new OwnerWorkspace(principal.getName(), workspace.get()));
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    private List<WorkspaceMembershipDto> activeOwnerMemberships(String userId) {
        if (!AccessRoles.INTERNAL.equalsIgnoreCase(userId)) {
            return identityStore.listMembershipsByUserId(userId).stream()
                    .filter(GitHubReadmeConnectorResource::isActiveOwner)
                    .toList();
        }
        return identityStore.listWorkspaces().stream()
                .map(workspace -> identityStore.listMembershipsByWorkspaceId(workspace.workspaceId()).stream()
                        .filter(GitHubReadmeConnectorResource::isActiveOwner)
                        .findFirst())
                .flatMap(Optional::stream)
                .toList();
    }

    private static boolean isActiveOwner(WorkspaceMembershipDto membership) {
        return "ACTIVE".equalsIgnoreCase(membership.status()) && AccessRoles.OWNER.equalsIgnoreCase(membership.role());
    }

    private Response rejectUnexpectedBody(InputStream requestBody) {
        if (requestBody == null) {
            return null;
        }
        try {
            if (requestBody.read() == -1) {
                return null;
            }
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("unexpected_body", "Refresh uses the stored connection settings"))
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
            case DISABLED ->
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(new AmshaErrorResponse(
                                "connector_disabled",
                                "GitHub documentation connector is disabled; set "
                                        + ConfigKeys.GITHUB_DOCS_CONNECTOR_ENABLED
                                        + "=true and restart Amsha to enable it"))
                        .build();
            case NOT_CONFIGURED ->
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(new AmshaErrorResponse(
                                "connector_not_configured", "GitHub App credentials are not configured"))
                        .build();
            case SOURCE_UNAVAILABLE ->
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(new AmshaErrorResponse(
                                "source_unavailable", "GitHub documentation refresh temporarily unavailable"))
                        .build();
            case INVALID_SOURCE_RESPONSE ->
                Response.status(Response.Status.BAD_GATEWAY)
                        .entity(new AmshaErrorResponse(
                                "invalid_source_response", "GitHub returned an invalid connector response"))
                        .build();
            case INDEX_UNAVAILABLE ->
                Response.serverError()
                        .entity(new AmshaErrorResponse(
                                "index_unavailable", "GitHub documentation index could not be updated"))
                        .build();
        };
    }

    private record OwnerWorkspace(String ownerUserId, String workspaceId) {}
}
