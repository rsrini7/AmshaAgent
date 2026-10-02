package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.security.ExternalUserProfile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link GithubIdentityProvider}.
 *
 * <p><strong>How the outbound path is covered.</strong> The provider is driven through a mocked
 * {@code jakarta.ws.rs.client.Client} rather than a real one. The field is {@code private final Client}, but
 * {@code Client} is an <em>interface</em>, so reflecting a Mockito chain
 * ({@code Client → WebTarget → Invocation.Builder}) into it reaches the whole {@code authenticate()} path with no
 * Quarkus container and no server. Nothing is ever marshalled — the stubbed {@code post(...)}/{@code get(...)}
 * return plain {@link Map}s and {@link List}s, which is exactly what the production code casts them to.
 *
 * <p>A real {@code ClientBuilder.newBuilder()…build()} client cannot do this: outside the Quarkus runtime it has no
 * JSON {@code MessageBodyReader} for {@code Map}, so every request dies on the mapping step before any of the
 * provider's logic runs. See {@code OidcIdentityProviderTest} for the sibling case.
 */
class GithubIdentityProviderTest {

    private static final String CLIENT_ID = "gh-client-123";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_URL = "https://api.github.com/user";
    private static final String EMAILS_URL = "https://api.github.com/user/emails";

    private GithubIdentityProvider provider;
    private Client mockHttpClient;

    @BeforeEach
    void setUp() throws Exception {
        provider = new GithubIdentityProvider();
        provider.enabled = true;
        provider.clientId = Optional.of(CLIENT_ID);
        provider.clientSecret = Optional.of("secret");
        provider.redirectUriConfig = Optional.empty();
        mockHttpClient = mock(Client.class);
        setField("httpClient", mockHttpClient);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = GithubIdentityProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(provider, value);
    }

    /** Stubs {@code client.target(url)}; the returned builder's {@code get}/{@code post} yield {@code payload}. */
    private Invocation.Builder stubEndpoint(String url, Object payload) {
        Invocation.Builder builder = mock(Invocation.Builder.class);
        when(builder.header(anyString(), any())).thenReturn(builder);
        doReturn(payload).when(builder).get(any(Class.class));
        doReturn(payload).when(builder).post(any(Entity.class), any(Class.class));
        WebTarget target = mock(WebTarget.class);
        when(target.request(any(MediaType[].class))).thenReturn(builder);
        when(mockHttpClient.target(url)).thenReturn(target);
        return builder;
    }

    /** Stubs a URL whose {@code request(...)} throws — used for the "email lookup is down" arm. */
    private void stubEndpointFailure(String url, RuntimeException failure) {
        WebTarget target = mock(WebTarget.class);
        when(target.request(any(MediaType[].class))).thenThrow(failure);
        when(mockHttpClient.target(url)).thenReturn(target);
    }

    private static Form capturedForm(Invocation.Builder builder) {
        ArgumentCaptor<Entity> captor = ArgumentCaptor.forClass(Entity.class);
        verify(builder).post(captor.capture(), any(Class.class));
        return (Form) captor.getValue().getEntity();
    }

    // ---------- identity ----------

    @Test
    void testProviderIdAndDisabledState() {
        provider.enabled = false;
        assertEquals("github", provider.getProviderId());
        assertFalse(provider.isEnabled());

        assertThrows(IllegalStateException.class, () -> provider.getAuthorizationUrl("state", "http://callback"));
        assertThrows(IllegalStateException.class, () -> provider.authenticate("code", "http://callback"));
    }

    @Test
    @DisplayName("close() closes the underlying JAX-RS client")
    void closeClosesTheHttpClient() {
        provider.close();

        verify(mockHttpClient).close();
    }

    // ---------- authorization URL ----------

    @Test
    void testGetAuthorizationUrl() {
        String url = provider.getAuthorizationUrl("state-123", "http://app.test/callback");

        assertTrue(url.startsWith("https://github.com/login/oauth/authorize?client_id=gh-client-123"), url);
        assertTrue(url.contains("state=state-123"), url);
        assertTrue(url.contains("scope=read:user+user:email"), url);
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Fapp.test%2Fcallback"), url);
    }

    @Test
    @DisplayName("the configured redirect URI outranks the one supplied on the request")
    void authorizationUrlUsesTheConfiguredRedirectUri() {
        provider.redirectUriConfig = Optional.of("https://app.example.com/callback");

        String url = provider.getAuthorizationUrl("state-1", "http://ignored.example/cb");

        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback"), url);
    }

    @Test
    @DisplayName("a blank configured redirect URI falls back to the one on the request")
    void authorizationUrlFallsBackToTheRequestRedirectWhenConfigIsBlank() {
        provider.redirectUriConfig = Optional.of("   ");

        String url = provider.getAuthorizationUrl("state-1", "http://cb.example/from-request");

        assertTrue(url.contains("redirect_uri=http%3A%2F%2Fcb.example%2Ffrom-request"), url);
    }

    @Test
    @DisplayName("a null state encodes to an empty parameter, not the text 'null'")
    void authorizationUrlEncodesANullStateAsEmpty() {
        String url = provider.getAuthorizationUrl(null, "http://cb");

        assertFalse(url.contains("null"), url);
        assertTrue(url.endsWith("state="), url);
    }

    @Test
    @DisplayName("special characters in the state are percent-encoded")
    void authorizationUrlEncodesSpecialCharacters() {
        String url = provider.getAuthorizationUrl("a b&c=d", "http://cb");

        assertTrue(url.contains("state=a+b%26c%3Dd"), url);
    }

    // ---------- token exchange ----------

    @Test
    @DisplayName("authenticate posts the code-exchange form to GitHub's token endpoint")
    void authenticatePostsTheTokenExchangeForm() throws Exception {
        Invocation.Builder tokenEndpoint = stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USER_URL, Map.of("id", 42));
        stubEndpoint(EMAILS_URL, List.of());

        provider.authenticate("code-1", "http://cb.example/from-request");

        Form form = capturedForm(tokenEndpoint);
        assertEquals(CLIENT_ID, form.asMap().getFirst("client_id"));
        assertEquals("secret", form.asMap().getFirst("client_secret"));
        assertEquals("code-1", form.asMap().getFirst("code"));
        assertEquals("http://cb.example/from-request", form.asMap().getFirst("redirect_uri"));
    }

    @Test
    @DisplayName("a blank configured redirect URI is treated as absent for the token exchange")
    void authenticateUsesTheRequestRedirectWhenConfigIsBlank() throws Exception {
        provider.redirectUriConfig = Optional.of("   ");
        Invocation.Builder tokenEndpoint = stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USER_URL, Map.of("id", 42));
        stubEndpoint(EMAILS_URL, List.of());

        provider.authenticate("code-1", "http://cb.example/from-request");

        assertEquals(
                "http://cb.example/from-request",
                capturedForm(tokenEndpoint).asMap().getFirst("redirect_uri"));
    }

    // ---------- profile assembly ----------

    @Test
    @DisplayName("authenticate returns the profile assembled from the user and emails endpoints")
    void authenticateReturnsTheProfileFromTheGitHubApis() {
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        Invocation.Builder user = stubEndpoint(
                USER_URL,
                Map.of("id", 42, "name", "Octo Cat", "avatar_url", "https://a/p.png", "email", "octo@example.com"));
        stubEndpoint(EMAILS_URL, List.of(Map.of("email", "octo@example.com", "verified", true, "primary", true)));

        ExternalUserProfile profile = provider.authenticate("code-1", "http://cb");

        assertEquals("42", profile.providerUserId());
        assertEquals("octo@example.com", profile.email());
        assertTrue(profile.emailVerified());
        assertEquals("Octo Cat", profile.displayName());
        assertEquals("https://a/p.png", profile.avatarUrl());

        ArgumentCaptor<Object> headerValue = ArgumentCaptor.forClass(Object.class);
        verify(user).header(eq("Authorization"), headerValue.capture());
        assertEquals("token at-1", headerValue.getValue());
    }

    @Test
    @DisplayName("a numeric GitHub id is rendered as its string form")
    void authenticateConvertsANumericIdToString() {
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USER_URL, Map.of("id", 987654321L));
        stubEndpoint(EMAILS_URL, List.of());

        assertEquals("987654321", provider.authenticate("code-1", "http://cb").providerUserId());
    }

    // ---------- email verification ----------

    @Test
    @DisplayName("the primary verified email replaces the profile's public one")
    void authenticateUsesThePrimaryVerifiedEmail() {
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USER_URL, Map.of("id", 42, "email", "public@example.com"));
        stubEndpoint(
                EMAILS_URL,
                List.of(
                        Map.of("email", "public@example.com", "verified", true, "primary", false),
                        Map.of("email", "primary@example.com", "verified", true, "primary", true)));

        ExternalUserProfile profile = provider.authenticate("code-1", "http://cb");

        assertEquals("primary@example.com", profile.email());
        assertTrue(profile.emailVerified());
    }

    @Test
    @DisplayName("a verified address matching neither the profile email nor primary leaves it unverified")
    void authenticateLeavesEmailUnverifiedWhenNothingMatches() {
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USER_URL, Map.of("id", 42, "email", "public@example.com"));
        stubEndpoint(EMAILS_URL, List.of(Map.of("email", "other@example.com", "verified", true)));

        ExternalUserProfile profile = provider.authenticate("code-1", "http://cb");

        assertEquals("public@example.com", profile.email());
        assertFalse(profile.emailVerified());
    }

    @Test
    @DisplayName("an unverified email entry is ignored even when it is the primary address")
    void authenticateIgnoresUnverifiedEmailEntries() {
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USER_URL, Map.of("id", 42, "email", "public@example.com"));
        stubEndpoint(
                EMAILS_URL,
                List.of(
                        Map.of("email", "public@example.com", "verified", false, "primary", true),
                        Map.of("email", "noverify@example.com")));

        ExternalUserProfile profile = provider.authenticate("code-1", "http://cb");

        assertEquals("public@example.com", profile.email());
        assertFalse(profile.emailVerified());
    }

    @Test
    @DisplayName("non-map and email-less entries in the emails list are skipped without failing")
    void authenticateSkipsUnusableEmailEntries() {
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USER_URL, Map.of("id", 42, "email", "public@example.com"));
        stubEndpoint(
                EMAILS_URL,
                List.<Object>of(
                        "not-a-map",
                        Map.of("verified", true),
                        Map.of("email", "public@example.com", "verified", true)));

        ExternalUserProfile profile = provider.authenticate("code-1", "http://cb");

        assertEquals("public@example.com", profile.email());
        assertTrue(profile.emailVerified());
    }

    @Test
    @DisplayName("a verified entry is still ignored when the profile exposes no email at all")
    void authenticateToleratesAMissingProfileEmail() {
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USER_URL, Map.of("id", 42));
        stubEndpoint(EMAILS_URL, List.of(Map.of("email", "octo@example.com", "verified", true)));

        ExternalUserProfile profile = provider.authenticate("code-1", "http://cb");

        assertNull(profile.email());
        assertFalse(profile.emailVerified());
    }

    @Test
    @DisplayName("a failing email-verification call degrades to an unverified profile instead of failing login")
    void authenticateToleratesAFailingEmailLookup() {
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USER_URL, Map.of("id", 42, "email", "public@example.com"));
        stubEndpointFailure(EMAILS_URL, new ProcessingException("emails endpoint down"));

        ExternalUserProfile profile = provider.authenticate("code-1", "http://cb");

        assertEquals("42", profile.providerUserId());
        assertEquals("public@example.com", profile.email());
        assertFalse(profile.emailVerified());
    }

    // ---------- failures ----------

    @Test
    @DisplayName("authenticate rejects a token response with no access_token")
    void authenticateRejectsATokenResponseWithoutAnAccessToken() {
        stubEndpoint(TOKEN_URL, Map.of("error", "bad_verification_code"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(
                ex.getCause().getMessage().contains("Access token missing"),
                ex.getCause().getMessage());
    }

    @Test
    @DisplayName("authenticate rejects a profile response without an id")
    void authenticateRejectsAProfileWithoutAnId() {
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USER_URL, Map.of("name", "Anonymous"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(
                ex.getCause().getMessage().contains("user ID missing"),
                ex.getCause().getMessage());
    }

    @Test
    @DisplayName("a transport failure surfaces as an IllegalStateException carrying the original cause")
    void authenticateWrapsTransportFailures() {
        stubEndpointFailure(TOKEN_URL, new ProcessingException("boom"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertInstanceOf(ProcessingException.class, ex.getCause());
    }
}
