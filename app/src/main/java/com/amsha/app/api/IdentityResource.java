package com.amsha.app.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityInvitationStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/identity")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class IdentityResource {

    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    IdentityStorePort identityStore;

    @Inject
    ApiCredentialStorePort apiCredentialStore;

    @Inject
    IdentityInvitationStorePort invitationStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Inject
    AuditService auditService;

    @Context
    SecurityContext securityContext;

    @GET
    @Path("/me")
    public Response getMe() {
        String userId = securityContext.getUserPrincipal().getName();
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Optional<IdentityUserDto> userOpt = identityStore.findUserById(userId);
        if (userOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("USER_NOT_FOUND", "User profile not found."))
                    .build();
        }

        List<WorkspaceMembershipDto> memberships = identityStore.listMembershipsByUserId(userId);
        return Response.ok(new MeResponse(userOpt.get(), memberships)).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/members")
    public Response listMembers(@PathParam("workspaceId") String workspaceId) {
        String userId = securityContext.getUserPrincipal().getName();
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        // Validate requester is a member of the workspace
        Optional<WorkspaceMembershipDto> requesterMembership = identityStore.findMembership(workspaceId, userId);
        if (requesterMembership.isEmpty()
                || !"ACTIVE".equals(requesterMembership.get().status())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "You are not an active member of this workspace."))
                    .build();
        }

        List<WorkspaceMembershipDto> members = identityStore.listMembershipsByWorkspaceId(workspaceId);
        return Response.ok(members).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/invitations")
    public Response createInvitation(@PathParam("workspaceId") String workspaceId, CreateInvitationRequest req) {

        String userId = securityContext.getUserPrincipal().getName();
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        // Verify requester is OWNER or ADMIN
        Optional<WorkspaceMembershipDto> membershipOpt = identityStore.findMembership(workspaceId, userId);
        if (membershipOpt.isEmpty() || !"ACTIVE".equals(membershipOpt.get().status())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "You are not an active member of this workspace."))
                    .build();
        }

        String role = membershipOpt.get().role();
        if (!"OWNER".equals(role) && !"ADMIN".equals(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Only workspace owners or admins can invite new members."))
                    .build();
        }

        // Only OWNER can invite/grant OWNER role
        if ("OWNER".equalsIgnoreCase(req.role()) && !"OWNER".equals(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Only workspace owners can invite new owners."))
                    .build();
        }
        // Inviter can only grant roles they hold (OWNER holds OWNER, ADMIN, MEMBER; ADMIN holds ADMIN, MEMBER)
        if (!"OWNER".equals(role)) {
            if (!"ADMIN".equalsIgnoreCase(req.role()) && !"MEMBER".equalsIgnoreCase(req.role())) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(new ErrorResponse(
                                "FORBIDDEN", "You cannot invite a user with a role higher than yours."))
                        .build();
            }
        }

        // Create invitation
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String rawToken = "inv-" + Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String tokenDigest = apiKeyRegistry.computeDigest(rawToken);

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(3600 * 24); // 24 hours

        if (req.role() == null
                || !Set.of("OWNER", "ADMIN", "MEMBER").contains(req.role().toUpperCase())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Invalid membership role requested."))
                    .build();
        }

        String invitationId =
                "inv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        IdentityInvitationDto invitation = new IdentityInvitationDto(
                invitationId,
                workspaceId,
                req.role(),
                tokenDigest,
                req.optionalEmailConstraint(),
                expiresAt,
                null,
                null,
                1,
                now,
                now);
        invitationStore.save(invitation);

        auditService.recordMandatory(
                "create-invitation",
                userId,
                "system",
                SecurityEventType.SECURITY,
                "action=create_invitation invitationId=" + invitationId + " workspaceId=" + workspaceId + " role="
                        + req.role());

        return Response.ok(new CreateInvitationResponse(invitationId, rawToken, expiresAt))
                .build();
    }

    @POST
    @Path("/invitations/{token}/accept")
    public Response acceptInvitation(@PathParam("token") String token) {
        String userId = securityContext.getUserPrincipal().getName();
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        String tokenDigest = apiKeyRegistry.computeDigest(token);
        Optional<IdentityInvitationDto> invitationOpt = invitationStore.findByTokenDigest(tokenDigest);

        if (invitationOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("INVITATION_NOT_FOUND", "Invitation not found."))
                    .build();
        }

        IdentityInvitationDto invitation = invitationOpt.get();
        Instant now = Instant.now();

        if (invitation.revokedAt() != null || invitation.expiresAt().isBefore(now) || invitation.acceptedBy() != null) {
            return Response.status(Response.Status.GONE)
                    .entity(new ErrorResponse("INVITATION_EXPIRED", "Invitation is expired, accepted, or revoked."))
                    .build();
        }

        // Add user to workspace membership
        WorkspaceMembershipDto membership =
                new WorkspaceMembershipDto(invitation.workspaceId(), userId, invitation.role(), "ACTIVE", 1, now, now);
        identityStore.saveMembership(membership);

        // Update invitation as accepted
        IdentityInvitationDto accepted = new IdentityInvitationDto(
                invitation.invitationId(),
                invitation.workspaceId(),
                invitation.role(),
                invitation.tokenDigest(),
                invitation.optionalEmailConstraint(),
                invitation.expiresAt(),
                userId,
                invitation.revokedAt(),
                invitation.revision(),
                invitation.createdAt(),
                now);
        invitationStore.save(accepted);

        auditService.recordMandatory(
                "accept-invitation",
                userId,
                "system",
                SecurityEventType.SECURITY,
                "action=accept_invitation invitationId=" + invitation.invitationId() + " workspaceId="
                        + invitation.workspaceId());

        return Response.ok(new AcceptInvitationResponse(true, invitation.workspaceId(), invitation.role()))
                .build();
    }

    @POST
    @Path("/api-credentials")
    public Response createApiCredential(CreateApiCredentialRequest req) {
        String userId = securityContext.getUserPrincipal().getName();
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        // Validate requested roleSet to prevent escalation
        String roleSet = req.roleSet();
        if (roleSet == null || roleSet.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_ROLES", "Role set cannot be empty."))
                    .build();
        }
        List<String> requestedRoles = Stream.of(roleSet.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (requestedRoles.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_ROLES", "Role set cannot be empty."))
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
                        .entity(new ErrorResponse(
                                "FORBIDDEN_ROLE", "The 'internal' role cannot be granted to API credentials."))
                        .build();
            }
            if (!allowedRoles.contains(upper)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(new ErrorResponse(
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

        // Register in cache
        apiKeyRegistry.register(userId, rawKey);

        auditService.recordMandatory(
                "create-api-credential",
                userId,
                "system",
                SecurityEventType.SECURITY,
                "action=create_api_credential credentialId=" + credentialId);

        return Response.ok(new CreateApiCredentialResponse(credentialId, rawKey, expiresAt))
                .build();
    }

    @DELETE
    @Path("/api-credentials/{credentialId}")
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

        return Response.ok(new RevokeResponse(true)).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/members/{memberId}")
    public Response removeMember(@PathParam("workspaceId") String workspaceId, @PathParam("memberId") String memberId) {

        String userId = securityContext.getUserPrincipal().getName();
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        // Verify requester is OWNER or ADMIN
        Optional<WorkspaceMembershipDto> membershipOpt = identityStore.findMembership(workspaceId, userId);
        if (membershipOpt.isEmpty() || !"ACTIVE".equals(membershipOpt.get().status())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "You are not an active member of this workspace."))
                    .build();
        }

        String role = membershipOpt.get().role();
        if (!"OWNER".equals(role) && !"ADMIN".equals(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Only owners or admins can manage members."))
                    .build();
        }

        Optional<WorkspaceMembershipDto> targetMembership = identityStore.findMembership(workspaceId, memberId);
        if (targetMembership.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        // Last active owner protection
        if ("OWNER".equals(targetMembership.get().role())) {
            List<WorkspaceMembershipDto> workspaceMembers = identityStore.listMembershipsByWorkspaceId(workspaceId);
            long ownerCount = workspaceMembers.stream()
                    .filter(m -> "OWNER".equals(m.role()) && "ACTIVE".equals(m.status()))
                    .count();
            if (ownerCount <= 1) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse(
                                "LAST_OWNER_REMOVAL_BLOCKED",
                                "Cannot remove or demote the last active owner of the workspace."))
                        .build();
            }
        }

        identityStore.deleteMembership(workspaceId, memberId);

        auditService.recordMandatory(
                "remove-member",
                userId,
                "system",
                SecurityEventType.AUTH_REJECTED,
                "action=remove_member workspaceId=" + workspaceId + " targetUserId=" + memberId);

        return Response.ok(new RevokeResponse(true)).build();
    }

    public record ErrorResponse(String code, String message) {}

    public record MeResponse(IdentityUserDto user, List<WorkspaceMembershipDto> memberships) {}

    public record CreateInvitationRequest(String role, String optionalEmailConstraint) {}

    public record CreateInvitationResponse(String invitationId, String rawToken, Instant expiresAt) {}

    public record AcceptInvitationResponse(boolean success, String workspaceId, String role) {}

    public record CreateApiCredentialRequest(String roleSet, Long expiresInSeconds) {}

    public record CreateApiCredentialResponse(String credentialId, String rawKey, Instant expiresAt) {}

    public record RevokeResponse(boolean success) {}
}
