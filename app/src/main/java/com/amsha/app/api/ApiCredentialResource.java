package com.amsha.app.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/identity/api-credentials")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class ApiCredentialResource {

    @Inject
    IdentityStorePort identityStore;

    @Inject
    ApiCredentialStorePort apiCredentialStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Inject
    AuditService auditService;

    @Context
    SecurityContext securityContext;

    private final SecureRandom secureRandom = new SecureRandom();

    @POST
    public Response createApiCredential(IdentityResource.CreateApiCredentialRequest req) {
        String userId = securityContext.getUserPrincipal().getName();
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        String roleSet = req.roleSet();
        if (roleSet == null || roleSet.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new IdentityResource.ErrorResponse("INVALID_ROLES", "Role set cannot be empty."))
                    .build();
        }
        List<String> requestedRoles = Stream.of(roleSet.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (requestedRoles.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new IdentityResource.ErrorResponse("INVALID_ROLES", "Role set cannot be empty."))
                    .build();
        }

        List<WorkspaceMembershipDto> memberships = identityStore.listMembershipsByUserId(userId);
        Set<String> allowedRoles = memberships.stream()
                .filter(m -> "ACTIVE".equalsIgnoreCase(m.status()))
                .map(WorkspaceMembershipDto::role)
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        for (String r : requestedRoles) {
            String upper = r.toUpperCase();
            if ("INTERNAL".equals(upper)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(new IdentityResource.ErrorResponse(
                                "FORBIDDEN_ROLE", "The 'internal' role cannot be granted to API credentials."))
                        .build();
            }
            if (!allowedRoles.contains(upper)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(new IdentityResource.ErrorResponse(
                                "FORBIDDEN_ROLE",
                                "You cannot grant the role '" + r + "' as you do not hold it in any active workspace."))
                        .build();
            }
        }

        byte[] keyBytes = new byte[32];
        secureRandom.nextBytes(keyBytes);
        String rawKey = "sk-" + Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);
        String keyDigest = apiKeyRegistry.computeDigest(rawKey);

        Instant now = Instant.now();
        Instant expiresAt = req.expiresInSeconds() != null ? now.plusSeconds(req.expiresInSeconds()) : null;

        String credentialId =
                "cred_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ApiCredentialDto cred =
                new ApiCredentialDto(credentialId, userId, keyDigest, req.roleSet(), 0, expiresAt, null, now, now);
        apiCredentialStore.save(cred);

        apiKeyRegistry.register(userId, rawKey);

        auditService.recordMandatory(
                "create-api-credential",
                userId,
                "system",
                SecurityEventType.SECURITY,
                "action=create_api_credential credentialId=" + credentialId);

        return Response.ok(new IdentityResource.CreateApiCredentialResponse(credentialId, rawKey, expiresAt))
                .build();
    }

    @DELETE
    @Path("/{credentialId}")
    public Response revokeApiCredential(@PathParam("credentialId") String credentialId) {
        String userId = securityContext.getUserPrincipal().getName();
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Optional<ApiCredentialDto> credOpt = apiCredentialStore.findById(credentialId);
        if (credOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        ApiCredentialDto cred = credOpt.get();
        if (!cred.userId().equals(userId)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        Instant now = Instant.now();
        ApiCredentialDto revoked = new ApiCredentialDto(
                cred.credentialId(),
                cred.userId(),
                cred.keyDigest(),
                cred.roleSet(),
                cred.revision(),
                cred.expiresAt(),
                now,
                cred.createdAt(),
                cred.updatedAt());
        apiCredentialStore.save(revoked);
        apiKeyRegistry.unregisterByDigest(cred.keyDigest());

        auditService.recordMandatory(
                "revoke-api-credential",
                userId,
                "system",
                SecurityEventType.AUTH_REJECTED,
                "action=revoke_api_credential credentialId=" + credentialId);

        return Response.ok(new IdentityResource.RevokeResponse(true)).build();
    }
}
