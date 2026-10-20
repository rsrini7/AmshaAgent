package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.security.ExternalUserProfile;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityInvitationStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;
import com.github.benmanes.caffeine.cache.Cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Branch-coverage suite for {@link AuthResource}: the identity-resolution ladder in
 * {@code resolveOrRegisterUser} (account linking and invitation registration), the
 * session-liveness predicate {@code isActive}, the null-email arm of {@code safeClaims}
 * and the post-login {@code PROVIDER_NOT_FOUND} arm of {@code callback}.
 *
 * <p>Complements {@code AuthResourceUnitTest} (entry guards) and the {@code @QuarkusTest}
 * {@code AuthResourceTest} (the end-to-end happy path). The private ladder is driven directly
 * because every arm raises a distinguishable {@link WebApplicationException}.
 */
@ExtendWith(MockitoExtension.class)
class AuthResourceBranchTest {

    private static final String PROVIDER = "oidc";
    private static final String SUBJECT = "sub-1";
    private static final String SESSION_TOKEN = "sess-tok";
    private static final String SESSION_DIGEST = "sess-digest";
    private static final String STATE = "state-1";
    private static final String INVITATION_TOKEN = "inv-tok";
    private static final String INVITATION_DIGEST = "inv-digest";

    @Mock
    Instance<ExternalIdentityProvider> providers;

    @Mock
    ExternalIdentityProvider provider1;

    @Mock
    IdentityStorePort identityStore;

    @Mock
    WebSessionStorePort webSessionStore;

    @Mock
    IdentityInvitationStorePort invitationStore;

    @Mock
    ApiKeyRegistry apiKeyRegistry;

    @Mock
    AuditService auditService;

    @Mock
    HttpHeaders httpHeaders;

    @Mock
    UriInfo uriInfo;

    @InjectMocks
    AuthResource resource;

    private final Instant now = Instant.now();

    /** Populated by the {@code saveExternalIdentity} answer; the registration user id is generated. */
    private final AtomicReference<ExternalIdentityDto> savedIdentity = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        resource.httpHeaders = httpHeaders;
        resource.uriInfo = uriInfo;
        resource.idleTimeoutMinutes = 720;
        lenient().when(httpHeaders.getCookies()).thenReturn(Map.of());
        lenient().when(provider1.getProviderId()).thenReturn(PROVIDER);
        lenient().when(provider1.isEnabled()).thenReturn(true);
    }

    // ---------------------------------------------------------------- callback

    @Test
    void callbackRejectsAProviderThatVanishedBetweenLoginAndCallback() throws Exception {
        cache("stateCache").put(STATE, PROVIDER);
        when(providers.stream()).thenReturn(Stream.empty());

        Response response = resource.callback(PROVIDER, "code-1", STATE);

        assertEquals(404, response.getStatus());
        assertEquals("PROVIDER_NOT_FOUND", ((AuthResource.ErrorResponse) response.getEntity()).code());
    }

    // ---------------------------------------------------- account linking arm

    @Test
    void linksANewExternalIdentityToTheActiveSessionUser() throws Exception {
        stubActiveSession("u1");
        stubIdentityLookups(Optional.empty(), Optional.of(identity("u1")));

        String userId = resolve(profile("Alice", "alice@example.com"), SESSION_TOKEN);

        assertEquals("u1", userId);
        ArgumentCaptor<ExternalIdentityDto> linked = ArgumentCaptor.forClass(ExternalIdentityDto.class);
        verify(identityStore).saveExternalIdentity(linked.capture());
        assertEquals("u1", linked.getValue().userId());
        assertEquals(SUBJECT, linked.getValue().subject());
        assertTrue(linked.getValue().safeClaims().contains("alice@example.com"));
        verify(auditService)
                .recordMandatory(eq("account-link"), eq("u1"), eq("system"), any(), contains("provider=" + PROVIDER));
    }

    @Test
    void linkRejectsWhenTheSavedIdentityBelongsToAnotherUser() {
        stubActiveSession("u1");
        stubIdentityLookups(Optional.empty(), Optional.of(identity("someone-else")));

        assertFailure(409, "IDENTITY_CONFLICT", resolveFailure(profile("Alice", "alice@example.com"), SESSION_TOKEN));
    }

    @Test
    void linkRejectsWhenTheSessionDigestIsUnknown() {
        when(apiKeyRegistry.computeDigest(SESSION_TOKEN)).thenReturn(SESSION_DIGEST);
        when(webSessionStore.findByTokenDigest(SESSION_DIGEST)).thenReturn(Optional.empty());
        stubIdentityLookups(Optional.empty(), null);

        assertFailure(403, "LINK_FAILED", resolveFailure(profile("Alice", "alice@example.com"), SESSION_TOKEN));
    }

    @Test
    void linkRejectsARevokedSession() {
        stubSession(SESSION_DIGEST, session("u1", now.plusSeconds(3600), now.plusSeconds(86400), now));
        stubIdentityLookups(Optional.empty(), null);

        assertFailure(403, "LINK_FAILED", resolveFailure(profile("Alice", "alice@example.com"), SESSION_TOKEN));
    }

    @Test
    void linkRejectsAnIdleExpiredSession() {
        stubSession(SESSION_DIGEST, session("u1", now.minusSeconds(1), now.plusSeconds(86400), null));
        stubIdentityLookups(Optional.empty(), null);

        assertFailure(403, "LINK_FAILED", resolveFailure(profile("Alice", "alice@example.com"), SESSION_TOKEN));
    }

    @Test
    void linkRejectsAnAbsolutelyExpiredSession() {
        stubSession(SESSION_DIGEST, session("u1", now.plusSeconds(3600), now.minusSeconds(1), null));
        stubIdentityLookups(Optional.empty(), null);

        assertFailure(403, "LINK_FAILED", resolveFailure(profile("Alice", "alice@example.com"), SESSION_TOKEN));
    }

    @Test
    void linkSerializesEmptyClaimsWhenTheProfileCarriesNoEmail() throws Exception {
        stubActiveSession("u1");
        stubIdentityLookups(Optional.empty(), Optional.of(identity("u1")));

        assertEquals("u1", resolve(profile("Alice", null), SESSION_TOKEN));

        ArgumentCaptor<ExternalIdentityDto> linked = ArgumentCaptor.forClass(ExternalIdentityDto.class);
        verify(identityStore).saveExternalIdentity(linked.capture());
        assertEquals("{}", linked.getValue().safeClaims());
    }

    // ------------------------------------------------- invitation registration

    @Test
    void registrationRejectsAnAlreadyAcceptedInvitation() throws Exception {
        seedInvitationToken();
        stubIdentityLookups(Optional.empty(), null);
        when(invitationStore.findByTokenDigest(INVITATION_DIGEST))
                .thenReturn(Optional.of(invitation("u1", null, now.plusSeconds(3600), null)));

        assertFailure(410, "INVITATION_ACCEPTED", resolveFailure(profile("Alice", "alice@example.com"), null));
    }

    @Test
    void registrationRejectsAnEmailConstraintMismatch() throws Exception {
        seedInvitationToken();
        stubIdentityLookups(Optional.empty(), null);
        when(invitationStore.findByTokenDigest(INVITATION_DIGEST))
                .thenReturn(Optional.of(invitation(null, "bob@example.com", now.plusSeconds(3600), null)));

        assertFailure(403, "EMAIL_CONSTRAINT_MISMATCH", resolveFailure(profile("Alice", "alice@example.com"), null));
    }

    @Test
    void registrationRejectsAConcurrentInvitationAcceptance() throws Exception {
        seedInvitationToken();
        stubIdentityLookups(Optional.empty(), null);
        when(invitationStore.findByTokenDigest(INVITATION_DIGEST))
                .thenReturn(Optional.of(invitation(null, null, now.plusSeconds(3600), null)));
        when(invitationStore.save(any(IdentityInvitationDto.class))).thenReturn(false);

        assertFailure(409, "INVITATION_CONFLICT", resolveFailure(profile("Alice", "alice@example.com"), null));
    }

    @Test
    void registrationRejectsAConcurrentIdentityLink() throws Exception {
        seedInvitationToken();
        stubIdentityLookups(Optional.empty(), Optional.of(identity("someone-else")));
        when(invitationStore.findByTokenDigest(INVITATION_DIGEST))
                .thenReturn(Optional.of(invitation(null, null, now.plusSeconds(3600), null)));
        when(invitationStore.save(any(IdentityInvitationDto.class))).thenReturn(true);

        assertFailure(409, "IDENTITY_CONFLICT", resolveFailure(profile("Alice", "alice@example.com"), null));
    }

    @Test
    void registrationRejectsAConcurrentMembershipChange() throws Exception {
        seedInvitationToken();
        stubRegistrationIdentityRoundTrip();
        when(invitationStore.findByTokenDigest(INVITATION_DIGEST))
                .thenReturn(Optional.of(invitation(null, null, now.plusSeconds(3600), null)));
        when(invitationStore.save(any(IdentityInvitationDto.class))).thenReturn(true);
        when(identityStore.saveMembership(any())).thenReturn(false);

        assertFailure(409, "MEMBERSHIP_CONFLICT", resolveFailure(profile("Alice", "alice@example.com"), null));
    }

    @Test
    void registrationRejectsAnExpiredInvitation() throws Exception {
        seedInvitationToken();
        stubIdentityLookups(Optional.empty(), null);
        when(invitationStore.findByTokenDigest(INVITATION_DIGEST))
                .thenReturn(Optional.of(invitation(null, null, now.minusSeconds(60), null)));

        assertFailure(403, "INVITATION_EXPIRED", resolveFailure(profile("Alice", "alice@example.com"), null));
    }

    @Test
    void registrationRejectsARevokedInvitation() throws Exception {
        seedInvitationToken();
        stubIdentityLookups(Optional.empty(), null);
        when(invitationStore.findByTokenDigest(INVITATION_DIGEST))
                .thenReturn(Optional.of(invitation(null, null, now.plusSeconds(3600), now)));

        assertFailure(403, "INVITATION_EXPIRED", resolveFailure(profile("Alice", "alice@example.com"), null));
    }

    @Test
    void registrationRejectsAnUnknownInvitationDigest() throws Exception {
        seedInvitationToken();
        stubIdentityLookups(Optional.empty(), null);
        when(invitationStore.findByTokenDigest(INVITATION_DIGEST)).thenReturn(Optional.empty());

        assertFailure(403, "INVITATION_INVALID", resolveFailure(profile("Alice", "alice@example.com"), null));
    }

    @Test
    void registrationRequiresAnInvitationToken() {
        stubIdentityLookups(Optional.empty(), null);

        assertFailure(403, "UNAUTHORIZED", resolveFailure(profile("Alice", "alice@example.com"), null));
    }

    // ------------------------------------------------------------- utilities

    private String resolve(ExternalUserProfile profile, String activeSessionToken) throws Exception {
        Method method = AuthResource.class.getDeclaredMethod(
                "resolveOrRegisterUser",
                String.class,
                String.class,
                ExternalUserProfile.class,
                String.class,
                String.class,
                Instant.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(resource, PROVIDER, PROVIDER, profile, activeSessionToken, STATE, now);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    /** Stubs the identity lookup so that the pre-check misses and the post-save verification answers. */
    private void stubIdentityLookups(Optional<ExternalIdentityDto> preCheck, Optional<ExternalIdentityDto> verify) {
        var stubbing = when(identityStore.findExternalIdentity(PROVIDER, PROVIDER, SUBJECT))
                .thenReturn(preCheck);
        if (verify != null) {
            stubbing.thenReturn(verify);
        }
    }

    /**
     * Registration path: capture the persisted identity and echo it back from the post-save
     * verification lookup, whose expected user id is only known after the fact.
     */
    private void stubRegistrationIdentityRoundTrip() {
        doAnswer(invocation -> {
                    savedIdentity.set(invocation.getArgument(0));
                    return null;
                })
                .when(identityStore)
                .saveExternalIdentity(any(ExternalIdentityDto.class));
        when(identityStore.findExternalIdentity(PROVIDER, PROVIDER, SUBJECT))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> Optional.ofNullable(savedIdentity.get()));
    }

    private void stubActiveSession(String userId) {
        stubSession(SESSION_DIGEST, session(userId, now.plusSeconds(3600), now.plusSeconds(86400), null));
    }

    private void stubSession(String digest, WebSessionDto session) {
        when(apiKeyRegistry.computeDigest(SESSION_TOKEN)).thenReturn(digest);
        when(webSessionStore.findByTokenDigest(digest)).thenReturn(Optional.of(session));
    }

    private void seedInvitationToken() throws Exception {
        cache("invitationCache").put(STATE, INVITATION_TOKEN);
        when(apiKeyRegistry.computeDigest(INVITATION_TOKEN)).thenReturn(INVITATION_DIGEST);
    }

    @SuppressWarnings("unchecked")
    private Cache<String, String> cache(String fieldName) throws Exception {
        Field field = AuthResource.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Cache<String, String>) field.get(resource);
    }

    private static WebSessionDto session(String userId, Instant idle, Instant absolute, Instant revoked) {
        Instant stamp = Instant.now();
        return new WebSessionDto("s1", userId, "digest", "csrf", idle, absolute, null, revoked, "{}", 1, stamp, stamp);
    }

    private static ExternalIdentityDto identity(String userId) {
        Instant stamp = Instant.now();
        return new ExternalIdentityDto(PROVIDER, PROVIDER, SUBJECT, userId, "{}", stamp, stamp);
    }

    private static IdentityInvitationDto invitation(
            String acceptedBy, String emailConstraint, Instant expiresAt, Instant revokedAt) {
        Instant stamp = Instant.now();
        return new IdentityInvitationDto(
                "inv-1",
                "ws-1",
                "MEMBER",
                INVITATION_DIGEST,
                emailConstraint,
                expiresAt,
                acceptedBy,
                revokedAt,
                1,
                stamp,
                stamp);
    }

    private static ExternalUserProfile profile(String displayName, String email) {
        return new ExternalUserProfile(SUBJECT, email, email != null, displayName, null);
    }

    private WebApplicationException resolveFailure(ExternalUserProfile profile, String sessionToken) {
        try {
            resolve(profile, sessionToken);
            throw new AssertionError("expected the identity-resolution ladder to reject");
        } catch (WebApplicationException expected) {
            return expected;
        } catch (RuntimeException unexpected) {
            throw unexpected;
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    private static void assertFailure(int expectedStatus, String expectedCode, WebApplicationException failure) {
        assertEquals(expectedStatus, failure.getResponse().getStatus());
        assertEquals(
                expectedCode,
                ((AuthResource.ErrorResponse) failure.getResponse().getEntity()).code());
    }
}
