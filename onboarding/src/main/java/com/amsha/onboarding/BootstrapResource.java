package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.BootstrapCredentialIssuer;
import com.amsha.core.security.IssuedCredential;
import com.amsha.core.security.PreparedBootstrapCredential;
import com.amsha.core.util.HttpConstants;
import com.amsha.persistence.api.ApiCredentialStorePort;
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

    private static final String BOOTSTRAP_ID = "bootstrap";

    @Inject
    BootstrapStateStorePort bootstrapStateStore;

    @Inject
    IdentityStorePort identityStore;

    @Inject
    Instance<ApiCredentialStorePort> apiCredentialStore;

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
        BootstrapStateDto state = stateOpt.orElseGet(() -> new BootstrapStateDto(
                BOOTSTRAP_ID, BootstrapStateDto.STATUS_UNCLAIMED, null, null, null, null, null, 0, Instant.now()));
        // Status discloses only state and safe revision; never owner/workspace ID, digest, or proof.
        return Response.ok(new BootstrapStatusResponse(state.status(), state.revision()))
                .build();
    }

    @POST
    @Path("/claim")
    public Response claim() {
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

        if (existing.isPresent() && existing.get().isClaiming()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse(
                            "CLAIM_IN_PROGRESS",
                            "Bootstrap claim is in progress. Use recovery/operator endpoints if stuck."))
                    .build();
        }

        // Recovery path: re-issue while CREDENTIAL_ISSUED.
        if (existing.isPresent() && existing.get().isCredentialIssued()) {
            return recover(existing.get());
        }

        if (existing.isPresent() && !existing.get().isUnclaimed()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse(
                            "ALREADY_CLAIMED", "Owner credential has already been claimed or issued."))
                    .build();
        }

        // Reservation first: reserve owner/workspace before creating any identity rows.
        String ownerId = "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String workspaceId =
                "ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant now = Instant.now();
        Optional<Integer> reserved = bootstrapStateStore.reserveFirstOwner(ownerId, workspaceId, now);
        if (reserved.isEmpty()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse("ALREADY_CLAIMED", "Owner claim lost in concurrent race."))
                    .build();
        }
        int reservationRevision = reserved.get();

        // Winner creates identity rows only after the reservation won.
        identityStore.saveUser(new IdentityUserDto(ownerId, "Initial Owner", "ACTIVE", now, now));
        identityStore.saveWorkspace(new IdentityWorkspaceDto(workspaceId, "Default Workspace", ownerId, now, now));
        identityStore.saveMembership(new WorkspaceMembershipDto(workspaceId, ownerId, "OWNER", "ACTIVE", 1, now, now));

        IssuedCredential issued = issueForReservedOwner(ownerId, reservationRevision);
        if (issued == null) {
            bootstrapStateStore.rollbackClaiming(reservationRevision, Instant.now());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("CLAIM_FAILED", "Failed to issue first owner credential."))
                    .build();
        }

        auditService.recordMandatory(
                "bootstrap-claim",
                ownerId,
                "system",
                SecurityEventType.ONBOARDING_COMPLETED,
                "action=claim_first_owner ownerId=" + ownerId + " workspaceId=" + workspaceId);

        NewCookie apiCookie = buildCookie(issued.rawKey());
        return Response.ok(new ClaimResponse(ownerId, workspaceId))
                .cookie(apiCookie)
                .build();
    }

    @POST
    @Path("/recovery")
    public Response recovery(@Context SecurityContext securityContext) {
        // Operator-only privileged path. Requires the configured internal operator key (resolved as
        // the claimed owner) and is accepted only while CLAIMING or unconfirmed CREDENTIAL_ISSUED.
        Optional<BootstrapStateDto> existing = bootstrapStateStore.getState();
        if (existing.isEmpty()
                || (!existing.get().isClaiming() && !existing.get().isCredentialIssued())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_STATE", "No recoverable bootstrap reservation."))
                    .build();
        }
        BootstrapStateDto state = existing.get();
        String expectedOwner = securityContext.getUserPrincipal() != null
                ? securityContext.getUserPrincipal().getName()
                : null;
        if (expectedOwner == null || !expectedOwner.equals(state.ownerId())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Recovery requires the reserved owner principal."))
                    .build();
        }
        // Rate-limited and mandatorily audited; no key/digest data in the audit record.
        auditService.recordMandatory(
                "bootstrap-recovery",
                state.ownerId(),
                "system",
                SecurityEventType.ONBOARDING_COMPLETED,
                "action=recover_claim ownerId=" + state.ownerId());
        return recover(state);
    }

    @POST
    @Path("/confirm")
    public Response confirm(@Context SecurityContext securityContext) {
        // Confirmation is bound to the authenticated issued principal (first draft creation also
        // calls this transition). No standalone unauthenticated confirm is permitted.
        Optional<BootstrapStateDto> existing = bootstrapStateStore.getState();
        if (existing.isEmpty() || !existing.get().isCredentialIssued()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_STATE", "Bootstrap state is not CREDENTIAL_ISSUED."))
                    .build();
        }
        BootstrapStateDto state = existing.get();
        String principal = securityContext.getUserPrincipal() != null
                ? securityContext.getUserPrincipal().getName()
                : null;
        if (principal == null || !principal.equals(state.ownerId())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("FORBIDDEN", "Confirm requires the reserved owner principal."))
                    .build();
        }
        Instant now = Instant.now();
        boolean confirmed = bootstrapStateStore.confirmClaim(state.revision(), state.ownerId(), now);
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

    private Response recover(BootstrapStateDto state) {
        // Revoke the prior bootstrap credential so re-activation can persist a fresh row; the old
        // digest is dropped from the registry by deleteById and becomes ineligible immediately.
        apiCredentialStore.get().findById("bootstrap-cred").ifPresent(cred -> {
            if (credentialIssuer.isResolvable()) {
                credentialIssuer.get().revoke(cred.keyDigest());
            }
        });
        apiCredentialStore.get().deleteById("bootstrap-cred");
        IssuedCredential newIssued = issueForReservedOwner(state.ownerId(), state.revision());
        if (newIssued == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("RECOVERY_FAILED", "Failed to recover first owner claim."))
                    .build();
        }
        NewCookie apiCookie = buildCookie(newIssued.rawKey());
        return Response.ok(new ClaimResponse(state.ownerId(), state.workspaceId()))
                .cookie(apiCookie)
                .build();
    }

    private IssuedCredential issueForReservedOwner(String ownerId, int reservationRevision) {
        PreparedBootstrapCredential prepared = credentialIssuer.get().prepare(ownerId);
        try {
            return credentialIssuer.get().activate(prepared, reservationRevision);
        } catch (Exception e) {
            log.error("Bootstrap credential activation failed: " + e.getMessage(), e);
            return null;
        } finally {
            prepared.close();
        }
    }

    private NewCookie buildCookie(String rawKey) {
        return new NewCookie.Builder(HttpConstants.COOKIE_AMSHA_API_KEY)
                .value(rawKey)
                .path("/")
                .maxAge(3600 * 24 * 30)
                .httpOnly(true)
                .secure(isSecureCookieRequired())
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    private boolean isSecureCookieRequired() {
        // Required on HTTPS/non-loopback deployments; not forced for explicit loopback HTTP dev.
        if (request == null || request.remoteAddress() == null) return true;
        String host = request.remoteAddress().host();
        boolean loopback = "127.0.0.1".equals(host) || "0:0:0:0:0:0:0:1".equals(host) || "localhost".equals(host);
        String scheme = request.scheme();
        return !(loopback && "http".equalsIgnoreCase(scheme));
    }

    private boolean isLoopbackRequest() {
        if (request == null || request.remoteAddress() == null) {
            return false;
        }
        String host = request.remoteAddress().host();
        return "127.0.0.1".equals(host) || "0:0:0:0:0:0:0:1".equals(host) || "localhost".equals(host);
    }

    public record BootstrapStatusResponse(String status, int revision) {}

    public record ClaimResponse(String ownerId, String workspaceId) {}

    public record ConfirmResponse(boolean success) {}

    public record ErrorResponse(String code, String message) {}
}
