package com.amsha.app.api;

import jakarta.ws.rs.core.Response;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Package-private helper for identity and workspace governance operations, token generation,
 * email claim matching, and credential rotation.
 */
final class IdentityResourceHelper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_ROLES = Set.of("OWNER", "ADMIN", "MEMBER");

    private IdentityResourceHelper() {}

    static String generateInvitationToken() {
        byte[] tokenBytes = new byte[32];
        try {
            new SecureRandom().nextBytes(tokenBytes);
            return "inv-" + Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        } finally {
            Arrays.fill(tokenBytes, (byte) 0);
        }
    }

    static String generateInvitationId() {
        return "inv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    static boolean isValidRole(String role) {
        return role != null && ALLOWED_ROLES.contains(role.toUpperCase(Locale.ROOT));
    }

    /**
     * Validates that {@code callerRole} may grant {@code requestedRole} by invitation.
     *
     * <p><b>Precondition:</b> {@code callerRole} must already be restricted to {@code OWNER} or {@code ADMIN} by
     * the caller. The only call site enforces this ({@code IdentityResource.createInvitation} rejects any other
     * active membership role before calling here), so this method is <em>not</em> self-defending — a caller role
     * outside that set is not rejected by it.
     *
     * <p>The third check below is unreachable for any input that passes the first two: the accepted roles are a
     * closed set of three, and the {@code OWNER} case is already handled above it. It is retained rather than
     * deleted, but it enforces nothing today. Making this method self-defending is a deliberate change, not a
     * refactor; see the finding recorded in {@code docs/TEST_COVERAGE_ANALYSIS.md}.
     *
     * @param callerRole the inviting member's role, expected to be {@code OWNER} or {@code ADMIN}
     * @param requestedRole the role to grant, or null
     * @return a 403 response if the invitation is not permitted, or null if it is
     */
    static Response validateInvitationRole(String callerRole, String requestedRole) {
        if (requestedRole == null || !isValidRole(requestedRole)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new IdentityResource.ErrorResponse("INVALID_ROLE", "Invalid membership role requested."))
                    .build();
        }
        String normalizedRole = requestedRole.toUpperCase(Locale.ROOT);
        if ("OWNER".equals(normalizedRole) && !"OWNER".equals(callerRole)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new IdentityResource.ErrorResponse(
                            "FORBIDDEN", "Only workspace owners can invite new owners."))
                    .build();
        }
        if (!"OWNER".equals(callerRole) && !"ADMIN".equals(normalizedRole) && !"MEMBER".equals(normalizedRole)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new IdentityResource.ErrorResponse(
                            "FORBIDDEN", "You cannot invite a user with a role higher than yours."))
                    .build();
        }
        return null;
    }

    static Response validateMemberRemoval(
            String callerRole, WorkspaceMembershipDto targetMembership, List<WorkspaceMembershipDto> workspaceMembers) {
        if ("ADMIN".equals(callerRole) && "OWNER".equals(targetMembership.role())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new IdentityResource.ErrorResponse("FORBIDDEN", "Admins cannot remove workspace owners."))
                    .build();
        }
        if ("OWNER".equals(targetMembership.role())) {
            long ownerCount = workspaceMembers.stream()
                    .filter(m -> "OWNER".equals(m.role()) && "ACTIVE".equals(m.status()))
                    .count();
            if (ownerCount <= 1) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new IdentityResource.ErrorResponse(
                                "LAST_OWNER_REMOVAL_BLOCKED",
                                "Cannot remove or demote the last active owner of the workspace."))
                        .build();
            }
        }
        return null;
    }

    static boolean matchesEmailConstraint(List<ExternalIdentityDto> externalIds, String emailConstraint) {
        if (emailConstraint == null || emailConstraint.isBlank()) {
            return true;
        }
        for (ExternalIdentityDto ext : externalIds) {
            String claims = ext.safeClaims();
            if (claims != null && claims.contains("\"email\"")) {
                VerifiedEmail verifiedEmail = parseVerifiedEmail(claims);
                if (verifiedEmail != null
                        && verifiedEmail.verified()
                        && emailConstraint.equalsIgnoreCase(
                                verifiedEmail.email().trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    static VerifiedEmail parseVerifiedEmail(String claims) {
        if (claims == null) {
            return null;
        }
        try {
            Map<String, Object> parsed = JSON.readValue(claims, new TypeReference<Map<String, Object>>() {});
            Object email = parsed.get("email");
            if (!(email instanceof String value) || value.isBlank()) {
                return null;
            }
            return new VerifiedEmail(value, Boolean.TRUE.equals(parsed.get("email_verified")));
        } catch (Exception _) {
            return null;
        }
    }

    static int rotateActiveCredentials(
            List<WorkspaceMembershipDto> members,
            ApiCredentialStorePort apiCredentialStore,
            ApiKeyRegistry apiKeyRegistry) {
        Instant now = Instant.now();
        int revokedCount = 0;
        for (WorkspaceMembershipDto member : members) {
            if (!"ACTIVE".equals(member.status())) {
                continue;
            }
            for (ApiCredentialDto cred : apiCredentialStore.listByUserId(member.userId())) {
                if (cred.revokedAt() != null) {
                    continue;
                }
                if (cred.expiresAt() != null && cred.expiresAt().isBefore(now)) {
                    continue;
                }
                apiCredentialStore.save(new ApiCredentialDto(
                        cred.credentialId(),
                        cred.userId(),
                        cred.keyDigest(),
                        cred.roleSet(),
                        cred.revision(),
                        cred.expiresAt(),
                        now,
                        cred.createdAt(),
                        now));
                apiKeyRegistry.unregisterByDigest(cred.keyDigest());
                revokedCount++;
            }
        }
        return revokedCount;
    }

    record VerifiedEmail(String email, boolean verified) {}
}
