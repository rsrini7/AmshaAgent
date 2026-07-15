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

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.ExternalIdentityDto;
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

        // Validate email constraint if present
        if (invitation.optionalEmailConstraint() != null
                && !invitation.optionalEmailConstraint().isBlank()) {
            List<ExternalIdentityDto> externalIds = identityStore.listExternalIdentitiesByUserId(userId);
            boolean emailMatched = false;
            for (ExternalIdentityDto ext : externalIds) {
                String claims = ext.safeClaims();
                if (claims != null && claims.contains("\"email\"")) {
                    String email = parseEmailFromClaims(claims);
                    if (email != null && invitation.optionalEmailConstraint().equalsIgnoreCase(email.trim())) {
                        emailMatched = true;
                        break;
                    }
                }
            }
            if (!emailMatched) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(new ErrorResponse(
                                "EMAIL_CONSTRAINT_MISMATCH",
                                "This invitation is restricted to a different email address."))
                        .build();
            }
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

    @POST
    @Path("/workspaces/{workspaceId}/owner/transfer")
    public Response transferOwnership(@PathParam("workspaceId") String workspaceId, TransferOwnerRequest req) {
        String userId = securityContext.getUserPrincipal().getName();
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        String targetUserId = req.targetUserId();
        if (targetUserId == null || targetUserId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_TARGET", "targetUserId is required"))
                    .build();
        }

        Optional<WorkspaceMembershipDto> callerMembership = identityStore.findMembership(workspaceId, userId);
        if (callerMembership.isEmpty()
                || !"ACTIVE".equals(callerMembership.get().status())
                || !"OWNER".equals(callerMembership.get().role())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Only an active owner can transfer ownership."))
                    .build();
        }

        Optional<WorkspaceMembershipDto> targetMembership = identityStore.findMembership(workspaceId, targetUserId);
        if (targetMembership.isEmpty()
                || !"ACTIVE".equals(targetMembership.get().status())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("TARGET_NOT_MEMBER", "Target must be an active member of the workspace."))
                    .build();
        }

        Instant now = Instant.now();
        identityStore.saveMembership(new WorkspaceMembershipDto(
                workspaceId,
                targetUserId,
                "OWNER",
                targetMembership.get().status(),
                targetMembership.get().revision(),
                targetMembership.get().createdAt(),
                now));

        if (!userId.equals(targetUserId)) {
            identityStore.saveMembership(new WorkspaceMembershipDto(
                    workspaceId,
                    userId,
                    "ADMIN",
                    callerMembership.get().status(),
                    callerMembership.get().revision(),
                    callerMembership.get().createdAt(),
                    now));
        }

        auditService.recordMandatory(
                "transfer-ownership",
                userId,
                "system",
                SecurityEventType.SECURITY,
                "action=transfer_ownership workspaceId=" + workspaceId + " toUserId=" + targetUserId);

        return Response.ok(new TransferOwnerResponse(true, targetUserId)).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/credentials/rotate")
    public Response rotateWorkspaceCredentials(@PathParam("workspaceId") String workspaceId) {
        String userId = securityContext.getUserPrincipal().getName();
        if (userId == null || "anonymous".equals(userId)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Optional<WorkspaceMembershipDto> callerMembership = identityStore.findMembership(workspaceId, userId);
        if (callerMembership.isEmpty()
                || !"ACTIVE".equals(callerMembership.get().status())
                || !"OWNER".equals(callerMembership.get().role())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Only an active owner can rotate workspace credentials."))
                    .build();
        }

        List<WorkspaceMembershipDto> members = identityStore.listMembershipsByWorkspaceId(workspaceId);
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

        auditService.recordMandatory(
                "rotate-workspace-credentials",
                userId,
                "system",
                SecurityEventType.SECURITY,
                "action=rotate_workspace_credentials workspaceId=" + workspaceId + " count=" + revokedCount);

        return Response.ok(new RotateCredentialsResponse(true, revokedCount)).build();
    }

    private String parseEmailFromClaims(String claims) {
        if (claims == null) return null;
        try {
            int idx = claims.indexOf("\"email\"");
            if (idx == -1) return null;
            int colonIdx = claims.indexOf(":", idx);
            if (colonIdx == -1) return null;
            int startQuote = claims.indexOf("\"", colonIdx);
            if (startQuote == -1) return null;
            int endQuote = claims.indexOf("\"", startQuote + 1);
            if (endQuote == -1) return null;
            return claims.substring(startQuote + 1, endQuote);
        } catch (Exception e) {
            return null;
        }
    }

    public record ErrorResponse(String code, String message) {}

    public record MeResponse(IdentityUserDto user, List<WorkspaceMembershipDto> memberships) {}

    public record CreateInvitationRequest(String role, String optionalEmailConstraint) {}

    public record CreateInvitationResponse(String invitationId, String rawToken, Instant expiresAt) {}

    public record AcceptInvitationResponse(boolean success, String workspaceId, String role) {}

    public record CreateApiCredentialRequest(String roleSet, Long expiresInSeconds) {}

    public record CreateApiCredentialResponse(String credentialId, String rawKey, Instant expiresAt) {}

    public record RevokeResponse(boolean success) {}

    public record TransferOwnerRequest(String targetUserId) {}

    public record TransferOwnerResponse(boolean success, String newOwnerId) {}

    public record RotateCredentialsResponse(boolean success, int revokedCount) {}
}
