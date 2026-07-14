package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.BootstrapCredentialIssuer;
import com.amsha.core.security.IssuedCredential;
import com.amsha.core.util.HttpConstants;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.jboss.logging.Logger;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.http.HttpServerRequest;

@Path("/onboarding/bootstrap")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class BootstrapResource {

    private static final Logger log = Logger.getLogger(BootstrapResource.class);

    @Inject
    BootstrapStateStorePort bootstrapStateStore;

    @Inject
    IdentityStorePort identityStore;

    @Inject
    Instance<BootstrapCredentialIssuer> credentialIssuer;

    @Inject
    AuditService auditService;

    @Inject
    HttpServerRequest request;

    @GET
    @Path("/status")
    public Response status() {
        Optional<BootstrapStateDto> stateOpt = bootstrapStateStore.getState();
        BootstrapStateDto state = stateOpt.orElseGet(
                () -> new BootstrapStateDto("bootstrap", "UNCLAIMED", "", "", null, null, 0, Instant.now()));

        return Response.ok(new BootstrapStatusResponse(state.status(), state.ownerId(), state.revision()))
                .build();
    }

    @POST
    @Path("/claim")
    public Response claim() {
        // Issue 5: Loopback-only guard
        if (!isLoopbackRequest()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse(
                            "FORBIDDEN", "Bootstrap claim is restricted to loopback (localhost) requests."))
                    .build();
        }

        if (!credentialIssuer.isResolvable()) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse(
                            "SPI_MISSING", "BootstrapCredentialIssuer SPI implementation is not available."))
                    .build();
        }

        Optional<BootstrapStateDto> existing = bootstrapStateStore.getState();
        String ownerId = "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String workspaceId =
                "ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant now = Instant.now();

        // Issue 2: Handle CREDENTIAL_ISSUED recovery path
        if (existing.isPresent() && "CREDENTIAL_ISSUED".equals(existing.get().status())) {
            BootstrapStateDto state = existing.get();
            String recoveryOwnerId = state.ownerId();
            // Re-issue credential (persisted & registered first)
            IssuedCredential newIssued = credentialIssuer.get().issueCredential(recoveryOwnerId);
            try {
                boolean replaced =
                        bootstrapStateStore.replaceCredential(state.revision(), newIssued.keyDigest(), now, now);
                if (!replaced) {
                    // Issue 1: Rollback unconfirmed orphaned credential if CAS check failed
                    credentialIssuer
                            .get()
                            .rollbackCredential(recoveryOwnerId, newIssued.keyDigest(), newIssued.rawKey());
                    return Response.status(Response.Status.CONFLICT)
                            .entity(new ErrorResponse("ALREADY_CLAIMED", "Bootstrap claim lost in concurrent race."))
                            .build();
                }

                // Overwrite the identities since this is a recovery overwrite
                identityStore.saveUser(new IdentityUserDto(recoveryOwnerId, "Initial Owner", "ACTIVE", now, now));
                identityStore.saveWorkspace(
                        new IdentityWorkspaceDto(workspaceId, "Default Workspace", recoveryOwnerId, now, now));
                identityStore.saveMembership(
                        new WorkspaceMembershipDto(workspaceId, recoveryOwnerId, "OWNER", "ACTIVE", 1, now, now));

                auditService.recordMandatory(
                        "bootstrap-recovery",
                        recoveryOwnerId,
                        "system",
                        SecurityEventType.ONBOARDING_COMPLETED,
                        "action=recover_claim ownerId=" + recoveryOwnerId + " workspaceId=" + workspaceId);

                // Issue 7: cookie is httpOnly and secure
                NewCookie apiCookie = new NewCookie.Builder(HttpConstants.COOKIE_AMSHA_API_KEY)
                        .value(newIssued.rawKey())
                        .path("/")
                        .maxAge(3600 * 24 * 30) // 30 days
                        .httpOnly(true)
                        .secure(true)
                        .sameSite(NewCookie.SameSite.STRICT)
                        .build();

                // Issue 4: ClaimResponse excludes rawKey in JSON response body
                return Response.ok(new ClaimResponse(recoveryOwnerId, workspaceId))
                        .cookie(apiCookie)
                        .build();

            } catch (Exception e) {
                // Issue 1: Rollback unconfirmed orphaned credential on exception
                credentialIssuer.get().rollbackCredential(recoveryOwnerId, newIssued.keyDigest(), newIssued.rawKey());
                try {
                    bootstrapStateStore.save(state);
                } catch (Exception re) {
                    log.error("Failed to restore bootstrap state during rollback: " + re.getMessage(), re);
                }
                log.error("Bootstrap recovery failed: " + e.getMessage(), e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(new ErrorResponse(
                                "RECOVERY_FAILED", "Failed to recover first owner claim: " + e.getMessage()))
                        .build();
            }
        }

        // Happy-path UNCLAIMED claim flow
        if (existing.isPresent() && !"UNCLAIMED".equals(existing.get().status())) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse(
                            "ALREADY_CLAIMED", "Owner credential has already been claimed or issued."))
                    .build();
        }

        IssuedCredential issued = credentialIssuer.get().issueCredential(ownerId);
        try {
            boolean claimed = bootstrapStateStore.claimFirstOwner(ownerId, issued.keyDigest(), now);
            if (!claimed) {
                // Issue 1: Rollback orphaned credential if CAS check failed
                credentialIssuer.get().rollbackCredential(ownerId, issued.keyDigest(), issued.rawKey());
                return Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorResponse("ALREADY_CLAIMED", "Owner claim lost in concurrent race."))
                        .build();
            }

            identityStore.saveUser(new IdentityUserDto(ownerId, "Initial Owner", "ACTIVE", now, now));
            identityStore.saveWorkspace(new IdentityWorkspaceDto(workspaceId, "Default Workspace", ownerId, now, now));
            identityStore.saveMembership(
                    new WorkspaceMembershipDto(workspaceId, ownerId, "OWNER", "ACTIVE", 1, now, now));

            auditService.recordMandatory(
                    "bootstrap-claim",
                    ownerId,
                    "system",
                    SecurityEventType.ONBOARDING_COMPLETED,
                    "action=claim_first_owner ownerId=" + ownerId + " workspaceId=" + workspaceId);

            // Issue 7: cookie is httpOnly and secure
            NewCookie apiCookie = new NewCookie.Builder(HttpConstants.COOKIE_AMSHA_API_KEY)
                    .value(issued.rawKey())
                    .path("/")
                    .maxAge(3600 * 24 * 30) // 30 days
                    .httpOnly(true)
                    .secure(true)
                    .sameSite(NewCookie.SameSite.STRICT)
                    .build();

            // Issue 4: ClaimResponse excludes rawKey in JSON response body
            return Response.ok(new ClaimResponse(ownerId, workspaceId))
                    .cookie(apiCookie)
                    .build();

        } catch (Exception e) {
            // Issue 1: Rollback on exception
            credentialIssuer.get().rollbackCredential(ownerId, issued.keyDigest(), issued.rawKey());
            try {
                bootstrapStateStore.save(
                        new BootstrapStateDto("bootstrap", "UNCLAIMED", "", "", null, null, 0, Instant.now()));
            } catch (Exception re) {
                log.error("Failed to restore bootstrap state during rollback: " + re.getMessage(), re);
            }
            log.error("Bootstrap claim failed: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("CLAIM_FAILED", "Failed to claim first owner: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/confirm")
    public Response confirm(ConfirmRequest req) {
        Optional<BootstrapStateDto> existing = bootstrapStateStore.getState();
        if (existing.isEmpty() || !"CREDENTIAL_ISSUED".equals(existing.get().status())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_STATE", "Bootstrap state is not CREDENTIAL_ISSUED."))
                    .build();
        }

        BootstrapStateDto state = existing.get();
        Instant now = Instant.now();
        boolean confirmed = bootstrapStateStore.confirmClaim(state.revision(), now, now);
        if (!confirmed) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse("CONFIRM_FAILED", "Failed to confirm claim, revision mismatch."))
                    .build();
        }

        auditService.recordMandatory(
                "bootstrap-confirm",
                state.ownerId(),
                "system",
                SecurityEventType.ONBOARDING_COMPLETED,
                "action=confirm_claim ownerId=" + state.ownerId());

        return Response.ok(new ConfirmResponse(true)).build();
    }

    private boolean isLoopbackRequest() {
        if (request == null || request.remoteAddress() == null) {
            return false;
        }
        String host = request.remoteAddress().host();
        return "127.0.0.1".equals(host) || "0:0:0:0:0:0:0:1".equals(host) || "localhost".equals(host);
    }

    public record BootstrapStatusResponse(String status, String ownerId, int revision) {}

    public record ClaimResponse(String ownerId, String workspaceId) {}

    public record ConfirmRequest(int expectedRevision) {}

    public record ConfirmResponse(boolean success) {}

    public record ErrorResponse(String code, String message) {}
}
