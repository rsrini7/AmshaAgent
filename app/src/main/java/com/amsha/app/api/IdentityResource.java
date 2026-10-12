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

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.AccessRoles;
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
            if (AccessRoles.INTERNAL.equalsIgnoreCase(userId)
                    || (securityContext != null && securityContext.isUserInRole(AccessRoles.INTERNAL))) {
                Instant epoch = Instant.EPOCH;
                IdentityUserDto internalUser = new IdentityUserDto(userId, "System Operator", "ACTIVE", epoch, epoch);
                return Response.ok(new MeResponse(internalUser, List.of())).build();
            }
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
        if (!AccessRoles.OWNER.equals(role) && !AccessRoles.ADMIN.equals(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Only workspace owners or admins can invite new members."))
                    .build();
        }

        Response roleError = IdentityResourceHelper.validateInvitationRole(role, req != null ? req.role() : null);
        if (roleError != null) {
            return roleError;
        }
        String invitedRole = req.role().toUpperCase(Locale.ROOT);

        // Create invitation
        String rawToken = IdentityResourceHelper.generateInvitationToken();
        String tokenDigest = apiKeyRegistry.computeDigest(rawToken);

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(3600 * 24); // 24 hours

        String invitationId = IdentityResourceHelper.generateInvitationId();
        IdentityInvitationDto invitation = new IdentityInvitationDto(
                invitationId,
                workspaceId,
                invitedRole,
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
                        + invitedRole);

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
            if (!IdentityResourceHelper.matchesEmailConstraint(externalIds, invitation.optionalEmailConstraint())) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(new ErrorResponse(
                                "EMAIL_CONSTRAINT_MISMATCH",
                                "This invitation is restricted to a different email address."))
                        .build();
            }
        }

        // Claim the invitation before granting access. The revision check makes replay atomic.
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
        if (!invitationStore.save(accepted)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse("INVITATION_CONFLICT", "Invitation was accepted by another request."))
                    .build();
        }

        Optional<WorkspaceMembershipDto> existingMembership =
                identityStore.findMembership(invitation.workspaceId(), userId);
        int membershipRevision =
                existingMembership.map(WorkspaceMembershipDto::revision).orElse(0);
        Instant membershipCreatedAt =
                existingMembership.map(WorkspaceMembershipDto::createdAt).orElse(now);
        WorkspaceMembershipDto membership = new WorkspaceMembershipDto(
                invitation.workspaceId(),
                userId,
                invitation.role(),
                "ACTIVE",
                membershipRevision,
                membershipCreatedAt,
                now);
        if (!identityStore.saveMembership(membership)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse("MEMBERSHIP_CONFLICT", "Workspace membership changed concurrently."))
                    .build();
        }

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
        if (!AccessRoles.OWNER.equals(role) && !AccessRoles.ADMIN.equals(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Only owners or admins can manage members."))
                    .build();
        }

        Optional<WorkspaceMembershipDto> targetMembership = identityStore.findMembership(workspaceId, memberId);
        if (targetMembership.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        List<WorkspaceMembershipDto> workspaceMembers =
                AccessRoles.OWNER.equals(targetMembership.get().role())
                        ? identityStore.listMembershipsByWorkspaceId(workspaceId)
                        : List.of();
        Response removalError =
                IdentityResourceHelper.validateMemberRemoval(role, targetMembership.get(), workspaceMembers);
        if (removalError != null) {
            return removalError;
        }

        if (identityStore.deleteMembershipIfNotLastOwner(workspaceId, memberId) == 0) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse("MEMBERSHIP_CONFLICT", "Membership changed concurrently."))
                    .build();
        }

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
                || !AccessRoles.OWNER.equals(callerMembership.get().role())) {
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
        boolean promoted = identityStore.saveMembership(new WorkspaceMembershipDto(
                workspaceId,
                targetUserId,
                AccessRoles.OWNER,
                targetMembership.get().status(),
                targetMembership.get().revision(),
                targetMembership.get().createdAt(),
                now));
        if (!promoted) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse("MEMBERSHIP_CONFLICT", "Target membership changed concurrently."))
                    .build();
        }

        if (!userId.equals(targetUserId)) {
            boolean demoted = identityStore.saveMembership(new WorkspaceMembershipDto(
                    workspaceId,
                    userId,
                    AccessRoles.ADMIN,
                    callerMembership.get().status(),
                    callerMembership.get().revision(),
                    callerMembership.get().createdAt(),
                    now));
            if (!demoted) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorResponse("MEMBERSHIP_CONFLICT", "Owner membership changed concurrently."))
                        .build();
            }
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
                || !AccessRoles.OWNER.equals(callerMembership.get().role())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Only an active owner can rotate workspace credentials."))
                    .build();
        }

        List<WorkspaceMembershipDto> members = identityStore.listMembershipsByWorkspaceId(workspaceId);
        int revokedCount = IdentityResourceHelper.rotateActiveCredentials(members, apiCredentialStore, apiKeyRegistry);

        auditService.recordMandatory(
                "rotate-workspace-credentials",
                userId,
                "system",
                SecurityEventType.SECURITY,
                "action=rotate_workspace_credentials workspaceId=" + workspaceId + " count=" + revokedCount);

        return Response.ok(new RotateCredentialsResponse(true, revokedCount)).build();
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
