package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.util.HttpConstants;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.security.BootstrapCredentialIssuer;
import com.amsha.security.IssuedCredential;
import com.amsha.security.PreparedBootstrapCredential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;

/**
 * Container-free unit tests for {@link BootstrapResource}.
 *
 * <p>The sibling {@code BootstrapResourceTest} is a {@code @QuarkusTest} that walks the happy path over HTTP. This
 * one drives the same class directly with mocked ports, which is what makes the <em>refusal</em> arms reachable: the
 * loopback guard, the SPI-missing guard, the lost-reservation race, activation failure, and every
 * {@code INVALID_STATE} / {@code FORBIDDEN} / {@code CONFLICT} branch. Over HTTP those are either impossible to
 * provoke (RestAssured always connects over loopback) or would need real state-machine races.
 *
 * <p>Every collaborator is a package-private {@code @Inject} field, so it is assigned directly — no CDI container
 * and no reflection. Only the loopback address needs the Vert.x {@link SocketAddress} factory.
 */
class BootstrapResourceUnitTest {

    private static final String OWNER_ID = "usr_0123456789abcdef";
    private static final String WORKSPACE_ID = "ws_0123456789abcdef";

    private BootstrapResource resource;
    private BootstrapStateStorePort stateStore;
    private IdentityStorePort identityStore;
    private ApiCredentialStorePort credentialStore;
    private BootstrapCredentialIssuer issuer;
    private Instance<ApiCredentialStorePort> apiCredentialStore;
    private Instance<BootstrapCredentialIssuer> credentialIssuer;
    private AuditService auditService;
    private HttpServerRequest request;

    @BeforeEach
    void setUp() {
        resource = new BootstrapResource();
        stateStore = mock(BootstrapStateStorePort.class);
        identityStore = mock(IdentityStorePort.class);
        credentialStore = mock(ApiCredentialStorePort.class);
        issuer = mock(BootstrapCredentialIssuer.class);
        apiCredentialStore = mockInstance();
        credentialIssuer = mockInstance();
        auditService = mock(AuditService.class);
        request = mock(HttpServerRequest.class);

        resource.bootstrapStateStore = stateStore;
        resource.identityStore = identityStore;
        resource.apiCredentialStore = apiCredentialStore;
        resource.credentialIssuer = credentialIssuer;
        resource.auditService = auditService;
        resource.request = request;
        resource.localRecoveryEnabled = false;

        when(apiCredentialStore.get()).thenReturn(credentialStore);
        when(credentialIssuer.get()).thenReturn(issuer);
        when(credentialIssuer.isResolvable()).thenReturn(true);
        fromLoopbackOver("http");
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> mockInstance() {
        return mock(Instance.class);
    }

    private void fromLoopbackOver(String scheme) {
        when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(8080, "127.0.0.1"));
        when(request.scheme()).thenReturn(scheme);
    }

    private static BootstrapStateDto state(String status, int revision) {
        return new BootstrapStateDto(
                "bootstrap", status, OWNER_ID, WORKSPACE_ID, "digest", null, null, revision, Instant.now());
    }

    private static SecurityContext principal(String name) {
        SecurityContext context = mock(SecurityContext.class);
        if (name != null) {
            Principal user = mock(Principal.class);
            when(user.getName()).thenReturn(name);
            when(context.getUserPrincipal()).thenReturn(user);
        }
        return context;
    }

    private static String errorCode(Response response) {
        return ((BootstrapResource.ErrorResponse) response.getEntity()).code();
    }

    private static NewCookie apiCookie(Response response) {
        return response.getCookies().get(HttpConstants.COOKIE_AMSHA_API_KEY);
    }

    private void givenIssuanceSucceeds() {
        when(issuer.prepare(anyString()))
                .thenAnswer(invocation -> new PreparedBootstrapCredential(
                        invocation.getArgument(0), "sk-testrawkey123", "sk-testrawkey123".getBytes(), "digest-hex"));
        when(issuer.activate(any(PreparedBootstrapCredential.class), anyInt()))
                .thenReturn(new IssuedCredential("sk-testrawkey123", "digest-hex"));
    }

    // ---------- status ----------

    @Test
    @DisplayName("status falls back to an UNCLAIMED default when no bootstrap row is persisted")
    void statusFallsBackToUnclaimedWhenNoStateIsPersisted() {
        when(stateStore.getState()).thenReturn(Optional.empty());

        Response response = resource.status();

        assertEquals(200, response.getStatus());
        BootstrapResource.BootstrapStatusResponse body =
                (BootstrapResource.BootstrapStatusResponse) response.getEntity();
        assertEquals(BootstrapStateDto.STATUS_UNCLAIMED, body.status());
        assertEquals(0, body.revision());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
    }

    @Test
    @DisplayName("status reports the persisted status and revision")
    void statusReportsThePersistedStatusAndRevision() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMING, 4)));

        BootstrapResource.BootstrapStatusResponse body =
                (BootstrapResource.BootstrapStatusResponse) resource.status().getEntity();

        assertEquals(BootstrapStateDto.STATUS_CLAIMING, body.status());
        assertEquals(4, body.revision());
    }

    // ---------- claim: refusals ----------

    @Test
    @DisplayName("claim is refused when there is no request context to inspect")
    void claimIsRefusedWhenThereIsNoRequestContext() {
        resource.request = null;

        Response response = resource.claim();

        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", errorCode(response));
    }

    @Test
    @DisplayName("claim is refused from a non-loopback address")
    void claimIsRefusedFromANonLoopbackAddress() {
        when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(8080, "192.0.2.10"));

        Response response = resource.claim();

        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", errorCode(response));
        verify(stateStore, never()).reserveFirstOwner(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("claim is refused with SPI_MISSING when no credential issuer is available")
    void claimIsRefusedWhenTheCredentialIssuerSpiIsMissing() {
        when(credentialIssuer.isResolvable()).thenReturn(false);

        Response response = resource.claim();

        assertEquals(500, response.getStatus());
        assertEquals("SPI_MISSING", errorCode(response));
    }

    @Test
    @DisplayName("claim conflicts while another claim is in progress")
    void claimConflictsWhileAClaimIsInProgress() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMING, 1)));

        Response response = resource.claim();

        assertEquals(409, response.getStatus());
        assertEquals("CLAIM_IN_PROGRESS", errorCode(response));
    }

    @Test
    @DisplayName("claim conflicts once the owner has already been claimed")
    void claimConflictsWhenTheOwnerIsAlreadyClaimed() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMED, 3)));

        Response response = resource.claim();

        assertEquals(409, response.getStatus());
        assertEquals("ALREADY_CLAIMED", errorCode(response));
    }

    @Test
    @DisplayName("claim takes the recovery path while CREDENTIAL_ISSUED rather than conflicting")
    void claimTakesTheRecoveryPathWhileCredentialIssued() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, 2)));
        givenIssuanceSucceeds();

        Response response = resource.claim();

        assertEquals(200, response.getStatus());
        BootstrapResource.ClaimResponse body = (BootstrapResource.ClaimResponse) response.getEntity();
        assertEquals(OWNER_ID, body.ownerId());
        assertEquals(WORKSPACE_ID, body.workspaceId());
        assertNotNull(apiCookie(response));
    }

    @Test
    @DisplayName("claim conflicts when it loses the reservation race")
    void claimConflictsWhenTheReservationRaceIsLost() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_UNCLAIMED, 0)));
        when(stateStore.reserveFirstOwner(anyString(), anyString(), any())).thenReturn(Optional.empty());

        Response response = resource.claim();

        assertEquals(409, response.getStatus());
        assertEquals("ALREADY_CLAIMED", errorCode(response));
        verify(identityStore, never()).saveUser(any());
    }

    @Test
    @DisplayName("claim rolls the reservation back when credential activation fails")
    void claimRollsBackWhenCredentialActivationFails() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_UNCLAIMED, 0)));
        when(stateStore.reserveFirstOwner(anyString(), anyString(), any())).thenReturn(Optional.of(5));
        when(issuer.prepare(anyString()))
                .thenAnswer(invocation -> new PreparedBootstrapCredential(
                        invocation.getArgument(0), "sk-raw", "sk-raw".getBytes(), "digest-hex"));
        when(issuer.activate(any(PreparedBootstrapCredential.class), anyInt()))
                .thenThrow(new IllegalStateException("reservation mismatch"));

        Response response = resource.claim();

        assertEquals(500, response.getStatus());
        assertEquals("CLAIM_FAILED", errorCode(response));
        verify(stateStore).rollbackClaiming(eq(5), any());
    }

    @Test
    @DisplayName("claim closes the prepared credential even when activation fails")
    void claimClosesThePreparedCredentialOnFailure() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_UNCLAIMED, 0)));
        when(stateStore.reserveFirstOwner(anyString(), anyString(), any())).thenReturn(Optional.of(5));
        PreparedBootstrapCredential prepared =
                new PreparedBootstrapCredential(OWNER_ID, "sk-raw", "sk-raw".getBytes(), "digest-hex");
        when(issuer.prepare(anyString())).thenReturn(prepared);
        when(issuer.activate(any(PreparedBootstrapCredential.class), anyInt()))
                .thenThrow(new IllegalStateException("boom"));

        resource.claim();

        assertTrue(prepared.isClosed(), "prepared credential must be closed so its key bytes are zeroed");
    }

    // ---------- claim: success ----------

    @Test
    @DisplayName("claim reserves, creates identity rows, issues the credential and audits the transition")
    void claimIssuesTheOwnerCredentialAndRecordsAnAuditEvent() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_UNCLAIMED, 0)));
        when(stateStore.reserveFirstOwner(anyString(), anyString(), any())).thenReturn(Optional.of(9));
        givenIssuanceSucceeds();

        Response response = resource.claim();

        assertEquals(200, response.getStatus());
        BootstrapResource.ClaimResponse body = (BootstrapResource.ClaimResponse) response.getEntity();
        assertTrue(body.ownerId().startsWith("usr_"), body.ownerId());
        assertTrue(body.workspaceId().startsWith("ws_"), body.workspaceId());
        assertEquals("sk-testrawkey123", body.apiKey());

        // Identity rows are created only after the reservation was won.
        verify(identityStore).saveUser(any());
        verify(identityStore).saveWorkspace(any());
        verify(identityStore).saveMembership(any());
        verify(auditService).recordMandatory(eq("bootstrap-claim"), anyString(), anyString(), any(), anyString());

        NewCookie cookie = apiCookie(response);
        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());
        assertEquals("/", cookie.getPath());
        assertFalse(cookie.isSecure(), "loopback over http must not force a Secure cookie");
    }

    @Test
    @DisplayName("the issued cookie is Secure when the request is not plain loopback http")
    void cookieIsSecureForLoopbackHttps() {
        fromLoopbackOver("https");
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_UNCLAIMED, 0)));
        when(stateStore.reserveFirstOwner(anyString(), anyString(), any())).thenReturn(Optional.of(1));
        givenIssuanceSucceeds();

        Response response = resource.claim();

        assertTrue(apiCookie(response).isSecure());
    }

    // ---------- recovery paths ----------

    @Test
    @DisplayName("recovery revokes the previous bootstrap credential before re-issuing")
    void recoverRevokesThePreviousCredentialWhenTheIssuerIsResolvable() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, 2)));
        when(credentialStore.findById("bootstrap-cred"))
                .thenReturn(Optional.of(new ApiCredentialDto(
                        "bootstrap-cred",
                        OWNER_ID,
                        "old-digest",
                        "OWNER",
                        1,
                        null,
                        null,
                        Instant.now(),
                        Instant.now())));
        givenIssuanceSucceeds();

        Response response = resource.claim();

        assertEquals(200, response.getStatus());
        verify(issuer).revoke("old-digest");
        verify(credentialStore).deleteById("bootstrap-cred");
    }

    @Test
    @DisplayName("recovery skips revocation when the issuer SPI is absent but still drops the stale row")
    void recoverSkipsRevocationWhenTheIssuerIsNotResolvable() {
        // Reached via localRecovery, not claim: claim() refuses outright with SPI_MISSING when the issuer is
        // unresolvable, so it never reaches recover() and this inner guard would look dead through that route.
        resource.localRecoveryEnabled = true;
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMED, 3)));
        when(credentialStore.findById("bootstrap-cred"))
                .thenReturn(Optional.of(new ApiCredentialDto(
                        "bootstrap-cred",
                        OWNER_ID,
                        "old-digest",
                        "OWNER",
                        1,
                        null,
                        null,
                        Instant.now(),
                        Instant.now())));
        when(credentialIssuer.isResolvable()).thenReturn(false);
        givenIssuanceSucceeds();

        Response response = resource.localRecovery();

        assertEquals(200, response.getStatus());
        verify(issuer, never()).revoke(anyString());
        verify(credentialStore).deleteById("bootstrap-cred");
    }

    @Test
    @DisplayName("recovery fails with RECOVERY_FAILED when the replacement credential cannot be issued")
    void recoverFailsWhenTheNewCredentialCannotBeIssued() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, 2)));
        when(issuer.prepare(anyString()))
                .thenAnswer(invocation -> new PreparedBootstrapCredential(
                        invocation.getArgument(0), "sk-raw", "sk-raw".getBytes(), "digest-hex"));
        when(issuer.activate(any(PreparedBootstrapCredential.class), anyInt()))
                .thenThrow(new IllegalStateException("boom"));

        Response response = resource.claim();

        assertEquals(500, response.getStatus());
        assertEquals("RECOVERY_FAILED", errorCode(response));
    }

    @Test
    @DisplayName("a recovery issued with no request context still marks the cookie Secure")
    void cookieIsSecureWhenThereIsNoRequestContext() {
        resource.request = null;
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMING, 2)));
        givenIssuanceSucceeds();

        Response response = resource.recovery(principal(OWNER_ID));

        assertEquals(200, response.getStatus());
        assertTrue(apiCookie(response).isSecure());
    }

    @Test
    @DisplayName("a recovery issued with an unknown remote address still marks the cookie Secure")
    void cookieIsSecureWhenTheRemoteAddressIsUnknown() {
        when(request.remoteAddress()).thenReturn(null);
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMING, 2)));
        givenIssuanceSucceeds();

        Response response = resource.recovery(principal(OWNER_ID));

        assertEquals(200, response.getStatus());
        assertTrue(apiCookie(response).isSecure());
    }

    // ---------- local recovery ----------

    @Test
    @DisplayName("local recovery is forbidden while it is disabled")
    void localRecoveryIsForbiddenWhenDisabled() {
        resource.localRecoveryEnabled = false;

        Response response = resource.localRecovery();

        assertEquals(403, response.getStatus());
        assertEquals("LOCAL_RECOVERY_DISABLED", errorCode(response));
    }

    @Test
    @DisplayName("local recovery is forbidden from a non-loopback address even when enabled")
    void localRecoveryIsForbiddenFromANonLoopbackAddress() {
        resource.localRecoveryEnabled = true;
        when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(8080, "192.0.2.10"));

        Response response = resource.localRecovery();

        assertEquals(403, response.getStatus());
        assertEquals("LOCAL_RECOVERY_DISABLED", errorCode(response));
    }

    @Test
    @DisplayName("local recovery rejects a bootstrap state that is not CLAIMED")
    void localRecoveryRejectsAStateThatIsNotClaimed() {
        resource.localRecoveryEnabled = true;
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, 2)));

        Response response = resource.localRecovery();

        assertEquals(400, response.getStatus());
        assertEquals("INVALID_STATE", errorCode(response));
    }

    @Test
    @DisplayName("local recovery rejects an absent bootstrap row")
    void localRecoveryRejectsAnAbsentBootstrapRow() {
        resource.localRecoveryEnabled = true;
        when(stateStore.getState()).thenReturn(Optional.empty());

        assertEquals(400, resource.localRecovery().getStatus());
    }

    @Test
    @DisplayName("local recovery re-issues the claimed owner credential and audits it")
    void localRecoveryReissuesTheClaimedOwnerCredential() {
        resource.localRecoveryEnabled = true;
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMED, 3)));
        givenIssuanceSucceeds();

        Response response = resource.localRecovery();

        assertEquals(200, response.getStatus());
        assertEquals(OWNER_ID, ((BootstrapResource.ClaimResponse) response.getEntity()).ownerId());
        verify(auditService)
                .recordMandatory(eq("bootstrap-local-recovery"), anyString(), anyString(), any(), anyString());
    }

    // ---------- operator recovery ----------

    @Test
    @DisplayName("operator recovery rejects a state that is neither CLAIMING nor CREDENTIAL_ISSUED")
    void recoveryRejectsAnUnrecoverableState() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMED, 3)));

        Response response = resource.recovery(principal(OWNER_ID));

        assertEquals(400, response.getStatus());
        assertEquals("INVALID_STATE", errorCode(response));
    }

    @Test
    @DisplayName("operator recovery rejects an absent bootstrap row")
    void recoveryRejectsAnAbsentBootstrapRow() {
        when(stateStore.getState()).thenReturn(Optional.empty());

        assertEquals(400, resource.recovery(principal(OWNER_ID)).getStatus());
    }

    @Test
    @DisplayName("operator recovery rejects an unauthenticated caller")
    void recoveryRejectsAMissingPrincipal() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMING, 2)));

        Response response = resource.recovery(principal(null));

        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", errorCode(response));
    }

    @Test
    @DisplayName("operator recovery rejects a principal that is not the reserved owner")
    void recoveryRejectsAForeignPrincipal() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMING, 2)));

        Response response = resource.recovery(principal("usr_someone-else"));

        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", errorCode(response));
        verify(stateStore, never()).confirmClaim(anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("operator recovery re-issues for the reserved owner principal")
    void recoveryReissuesForTheReservedOwner() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMING, 2)));
        givenIssuanceSucceeds();

        Response response = resource.recovery(principal(OWNER_ID));

        assertEquals(200, response.getStatus());
        assertEquals(OWNER_ID, ((BootstrapResource.ClaimResponse) response.getEntity()).ownerId());
        verify(auditService).recordMandatory(eq("bootstrap-recovery"), anyString(), anyString(), any(), anyString());
    }

    // ---------- confirm ----------

    @Test
    @DisplayName("confirm rejects a state that is not CREDENTIAL_ISSUED")
    void confirmRejectsAStateThatIsNotCredentialIssued() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_UNCLAIMED, 0)));

        Response response = resource.confirm(principal(OWNER_ID));

        assertEquals(400, response.getStatus());
        assertEquals("INVALID_STATE", errorCode(response));
    }

    @Test
    @DisplayName("confirm rejects an unauthenticated caller")
    void confirmRejectsAMissingPrincipal() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, 2)));

        Response response = resource.confirm(principal(null));

        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", errorCode(response));
    }

    @Test
    @DisplayName("confirm rejects a principal that is not the reserved owner")
    void confirmRejectsAForeignPrincipal() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, 2)));

        Response response = resource.confirm(principal("usr_someone-else"));

        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", errorCode(response));
        verify(stateStore, never()).confirmClaim(anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("confirm reports a conflict when the revision no longer matches")
    void confirmReportsARevisionMismatch() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, 2)));
        when(stateStore.confirmClaim(eq(2), eq(OWNER_ID), any())).thenReturn(false);

        Response response = resource.confirm(principal(OWNER_ID));

        assertEquals(409, response.getStatus());
        assertEquals("CONFIRM_FAILED", errorCode(response));
    }

    @Test
    @DisplayName("confirm completes the claim and audits the transition")
    void confirmCompletesTheClaim() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, 2)));
        when(stateStore.confirmClaim(eq(2), eq(OWNER_ID), any())).thenReturn(true);

        Response response = resource.confirm(principal(OWNER_ID));

        assertEquals(200, response.getStatus());
        assertTrue(((BootstrapResource.ConfirmResponse) response.getEntity()).success());
        verify(auditService).recordMandatory(eq("bootstrap-confirm"), anyString(), anyString(), any(), anyString());
    }

    // ---------- remaining branch arms ----------
    //
    // These five cover short-circuit paths that the tests above leave dark: the `isPresent()`/`isEmpty()`-false
    // operands of the state guards in claim() and confirm(), the second operand of isLoopbackRequest()'s null
    // guard, the `isCredentialIssued()` operand of recovery()'s state guard, and the non-loopback operand of
    // isSecureCookieRequired()'s `loopback && http` conjunction (reachable only through recovery(), which — unlike
    // claim() and localRecovery() — has no loopback guard of its own).

    @Test
    @DisplayName("claim proceeds when no bootstrap row exists yet")
    void claimProceedsWhenNoBootstrapRowExists() {
        when(stateStore.getState()).thenReturn(Optional.empty());
        when(stateStore.reserveFirstOwner(anyString(), anyString(), any())).thenReturn(Optional.of(1));
        givenIssuanceSucceeds();

        Response response = resource.claim();

        assertEquals(200, response.getStatus());
        verify(identityStore).saveUser(any());
    }

    @Test
    @DisplayName("claim is refused when the remote address cannot be resolved")
    void claimIsRefusedWhenTheRemoteAddressIsUnavailable() {
        when(request.remoteAddress()).thenReturn(null);

        Response response = resource.claim();

        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", errorCode(response));
        verify(stateStore, never()).reserveFirstOwner(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("operator recovery re-issues while CREDENTIAL_ISSUED as well as while CLAIMING")
    void recoveryReissuesWhileCredentialIssued() {
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, 2)));
        givenIssuanceSucceeds();

        Response response = resource.recovery(principal(OWNER_ID));

        assertEquals(200, response.getStatus());
        assertEquals(OWNER_ID, ((BootstrapResource.ClaimResponse) response.getEntity()).ownerId());
    }

    @Test
    @DisplayName("confirm rejects an absent bootstrap row")
    void confirmRejectsAnAbsentBootstrapRow() {
        when(stateStore.getState()).thenReturn(Optional.empty());

        Response response = resource.confirm(principal(OWNER_ID));

        assertEquals(400, response.getStatus());
        assertEquals("INVALID_STATE", errorCode(response));
    }

    @Test
    @DisplayName("an operator recovery from a non-loopback address still marks the cookie Secure")
    void cookieIsSecureForANonLoopbackRecovery() {
        // recovery() is the one entry point with no loopback guard, so this is the only route that reaches
        // isSecureCookieRequired() with a non-loopback host.
        when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(8080, "192.0.2.10"));
        when(request.scheme()).thenReturn("http");
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMING, 2)));
        givenIssuanceSucceeds();

        Response response = resource.recovery(principal(OWNER_ID));

        assertEquals(200, response.getStatus());
        assertTrue(apiCookie(response).isSecure());
    }
}
