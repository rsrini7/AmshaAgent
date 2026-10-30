package com.amsha.app.connector.github;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.io.InputStream;
import java.security.Principal;
import java.util.Arrays;

import com.amsha.common.config.ConfigKeys;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.AccessRoles;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.common.annotation.RunOnVirtualThread;

/** Internal-only lifecycle for the system-scoped GitHub App private key. */
@Path("/admin/connectors/github-readme/app-credential")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class GitHubAppCredentialResource {

    private static final int MAX_PRIVATE_KEY_BYTES = 32_768;

    private final SecretVault secretVault;
    private final AuditService auditService;
    private final GitHubReadmeStartupSync startupSync;
    private final boolean connectorEnabled;

    @Context
    SecurityContext securityContext;

    @Inject
    public GitHubAppCredentialResource(
            SecretVault secretVault,
            AuditService auditService,
            GitHubReadmeStartupSync startupSync,
            @ConfigProperty(name = ConfigKeys.GITHUB_DOCS_CONNECTOR_ENABLED_PROPERTY, defaultValue = "false")
                    boolean connectorEnabled) {
        this.secretVault = secretVault;
        this.auditService = auditService;
        this.startupSync = startupSync;
        this.connectorEnabled = connectorEnabled;
    }

    @GET
    public Response status() {
        if (internalActor() == null) {
            return forbidden();
        }
        try {
            return Response.ok(new GitHubAppCredentialStatusResponse(
                            connectorEnabled, secretVault.systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY)))
                    .header("Cache-Control", "no-store")
                    .build();
        } catch (RuntimeException _) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new AmshaErrorResponse(
                            "credential_status_unavailable", "GitHub App credential status is unavailable"))
                    .build();
        }
    }

    @PUT
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response store(InputStream requestBody) {
        String actor = internalActor();
        if (actor == null) {
            return forbidden();
        }
        byte[] keyBytes;
        try {
            keyBytes = requestBody == null ? new byte[0] : requestBody.readNBytes(MAX_PRIVATE_KEY_BYTES + 1);
        } catch (IOException _) {
            return badRequest("invalid_private_key", "GitHub App private key could not be read");
        }
        if (keyBytes.length == 0 || keyBytes.length > MAX_PRIVATE_KEY_BYTES) {
            Arrays.fill(keyBytes, (byte) 0);
            return badRequest("invalid_private_key", "GitHub App private key size is invalid");
        }
        try {
            try {
                GitHubAppJwtSigner.validatePrivateKey(keyBytes);
            } catch (IllegalArgumentException _) {
                return badRequest("invalid_private_key", "GitHub App private key is invalid");
            }
            auditService.recordMandatory(
                    null,
                    actor,
                    "github-readme-credential-admin",
                    SecurityEventType.VAULT_ACCESS,
                    "operation=store keyName=" + VaultConstants.GITHUB_APP_PRIVATE_KEY);
            secretVault.storeSystemKey(VaultConstants.GITHUB_APP_PRIVATE_KEY, keyBytes);
            startupSync.syncAllWorkspacesAsync();
            return Response.ok(new GitHubAppCredentialStatusResponse(connectorEnabled, true))
                    .build();
        } catch (RuntimeException _) {
            return Response.serverError()
                    .entity(new AmshaErrorResponse(
                            "credential_store_failed", "GitHub App credential could not be stored"))
                    .build();
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    @DELETE
    public Response remove() {
        String actor = internalActor();
        if (actor == null) {
            return forbidden();
        }
        try {
            auditService.recordMandatory(
                    null,
                    actor,
                    "github-readme-credential-admin",
                    SecurityEventType.VAULT_ACCESS,
                    "operation=remove keyName=" + VaultConstants.GITHUB_APP_PRIVATE_KEY);
            secretVault.removeSystemKey(VaultConstants.GITHUB_APP_PRIVATE_KEY);
            return Response.noContent().build();
        } catch (RuntimeException _) {
            return Response.serverError()
                    .entity(new AmshaErrorResponse(
                            "credential_remove_failed", "GitHub App credential could not be removed"))
                    .build();
        }
    }

    private String internalActor() {
        if (securityContext == null || !securityContext.isUserInRole(AccessRoles.INTERNAL)) {
            return null;
        }
        Principal principal = securityContext.getUserPrincipal();
        return principal == null
                        || principal.getName() == null
                        || principal.getName().isBlank()
                ? null
                : principal.getName();
    }

    private static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new AmshaErrorResponse("forbidden", "Internal access is required"))
                .build();
    }

    private static Response badRequest(String code, String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new AmshaErrorResponse(code, message))
                .build();
    }
}
