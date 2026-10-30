package com.amsha.app.security;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.IdentityStorePort;

/** Binds an explicitly selected identity workspace after authentication. */
@Provider
@Priority(Priorities.AUTHENTICATION + 10)
public class ActiveWorkspaceFilter implements ContainerRequestFilter {

    public static final String WORKSPACE_HEADER = "X-Amsha-Workspace-Id";

    @Inject
    ActiveWorkspaceContext activeWorkspaceContext;

    @Inject
    IdentityStorePort identityStore;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String workspaceId = requestContext.getHeaderString(WORKSPACE_HEADER);
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        if (requestContext.getSecurityContext().getUserPrincipal() == null) {
            requestContext.abortWith(
                    Response.status(Response.Status.UNAUTHORIZED).build());
            return;
        }
        String userId = requestContext.getSecurityContext().getUserPrincipal().getName();
        boolean activeMember = identityStore
                .findMembership(workspaceId, userId)
                .map(membership -> "ACTIVE".equalsIgnoreCase(membership.status()))
                .orElse(false);
        if (!activeMember && !isInternalGitHubConnectorOwnerScope(requestContext, workspaceId)) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN).build());
            return;
        }
        activeWorkspaceContext.setWorkspaceId(workspaceId);
    }

    private boolean isInternalGitHubConnectorOwnerScope(ContainerRequestContext requestContext, String workspaceId) {
        var securityContext = requestContext.getSecurityContext();
        var principal = securityContext.getUserPrincipal();
        if (principal == null
                || !AccessRoles.INTERNAL.equalsIgnoreCase(principal.getName())
                || !securityContext.isUserInRole(AccessRoles.INTERNAL)
                || !isGitHubConnectorPath(requestContext.getUriInfo().getPath())) {
            return false;
        }
        return identityStore.listMembershipsByWorkspaceId(workspaceId).stream()
                .anyMatch(membership -> "ACTIVE".equalsIgnoreCase(membership.status())
                        && AccessRoles.OWNER.equalsIgnoreCase(membership.role()));
    }

    private static boolean isGitHubConnectorPath(String path) {
        String normalized = path == null ? "" : path;
        if (normalized.startsWith("/api/v1/")) {
            normalized = normalized.substring(7);
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.equals("/connectors/github-readme") || normalized.startsWith("/connectors/github-readme/");
    }
}
