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
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.security.ExternalUserProfile;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link OidcIdentityProvider}.
 *
 * <p>Covers the surface that is reachable without the Quarkus container: provider identity, authorization-URL
 * construction (including PKCE, nonce, redirect resolution and percent-encoding), the RS256-only algorithm guard,
 * PKCE S256 derivation, JWT payload decoding, and every arm of id_token claim validation.
 *
 * <p><strong>How {@code authenticate} is covered.</strong> A <em>real</em>
 * {@code ClientBuilder.newBuilder()…build()} client cannot be driven from a plain unit test: outside the Quarkus
 * runtime it has no {@code MessageBodyReader} for {@code Map} + {@code application/json}, so every request dies
 * before the provider's logic runs:
 *
 * <pre>
 * Response could not be mapped to type interface java.util.Map for response with media type application/json.
 * </pre>
 *
 * That wall is a property of the <em>implementation</em>, not of the field. {@code httpClient} is declared
 * {@code private final Client}, and {@code jakarta.ws.rs.client.Client} is an interface, so replacing it with a
 * Mockito mock chain ({@code Client → WebTarget → Invocation.Builder}) makes the whole path reachable — nothing is
 * ever marshalled, because the stubbed {@code post(...)}/{@code get(...)} calls return plain {@link Map}s, which is
 * exactly the type the production code casts them to. id_tokens are signed with a genuine RSA key pair whose public
 * half is served as the JWKS, so the RS256 verification runs for real rather than being stubbed.
 */
class OidcIdentityProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "https://idp.example.com";
    private static final String CLIENT_ID = "client-123";
    private static final String TOKEN_URL = "https://idp.example.com/token";
    private static final String USERINFO_URL = "https://idp.example.com/userinfo";
    private static final String JWKS_URL = "https://idp.example.com/jwks";
    private static final String KID = "kid-1";

    private OidcIdentityProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new OidcIdentityProvider();
        setField("enabled", true);
        setField("clientId", Optional.of(CLIENT_ID));
        setField("clientSecret", Optional.of("secret-456"));
        setField("clientSecretVaultKey", Optional.empty());
        setField("authorizationUrl", Optional.of("https://idp.example.com/auth"));
        setField("tokenUrl", Optional.of(TOKEN_URL));
        setField("userinfoUrl", Optional.of(USERINFO_URL));
        setField("issuer", Optional.of(ISSUER));
        setField("jwksUrl", Optional.of(JWKS_URL));
        setField("redirectUriConfig", Optional.of("https://app.example.com/callback"));
        provider.secretVault = mock(SecretVault.class);
    }

    private void setField(String name, Object val) throws Exception {
        Field f = OidcIdentityProvider.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(provider, val);
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A three-segment JWT-shaped token; only the payload segment is ever decoded by the code under test. */
    private static String token(Map<String, Object> header, Map<String, Object> claims) throws Exception {
        return b64(JSON.writeValueAsBytes(header))
                + "."
                + b64(JSON.writeValueAsBytes(claims))
                + "."
                + b64("signature".getBytes(StandardCharsets.US_ASCII));
    }

    private static Map<String, Object> validClaims() {
        return Map.of(
                "sub",
                "user-1",
                "email",
                "user@example.com",
                "email_verified",
                true,
                "name",
                "Test User",
                "picture",
                "https://example.com/p.png",
                "exp",
                Instant.now().getEpochSecond() + 3600,
                "iss",
                ISSUER,
                "aud",
                CLIENT_ID);
    }

    // ---------- identity ----------

    @Test
    void testBasicProperties() {
        assertEquals("oidc", provider.getProviderId());
        assertTrue(provider.isEnabled());
    }

    @Test
    void testCloseNoThrow() {
        assertDoesNotThrow(provider::close);
    }

    @Test
    @DisplayName("both entry points refuse to work while the provider is disabled")
    void testGetAuthorizationUrlDisabledThrows() throws Exception {
        setField("enabled", false);
        assertThrows(IllegalStateException.class, () -> provider.getAuthorizationUrl("state", "http://cb"));
        assertThrows(IllegalStateException.class, () -> provider.authenticate("code", "http://cb"));
    }

    // ---------- authorization URL ----------

    @Test
    void testGetAuthorizationUrlWithPkceAndNonce() {
        String url = provider.getAuthorizationUrl("state-123", "http://cb", "challenge-xyz", "nonce-999");
        assertTrue(url.contains("client_id=client-123"));
        assertTrue(url.contains("state=state-123"));
        assertTrue(url.contains("code_challenge=challenge-xyz"));
        assertTrue(url.contains("nonce=nonce-999"));
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback"));
    }

    @Test
    @DisplayName("blank PKCE challenge and nonce are omitted from the authorization URL")
    void authorizationUrlOmitsBlankPkceAndNonce() {
        String url = provider.getAuthorizationUrl("state-1", "http://cb", "   ", "");

        assertFalse(url.contains("code_challenge"), url);
        assertFalse(url.contains("nonce"), url);
        assertTrue(url.contains("response_type=code"), url);
        assertTrue(url.contains("scope=openid+email+profile"), url);
    }

    @Test
    @DisplayName("a null state and an empty client id encode to empty parameters, not the text 'null'")
    void authorizationUrlEncodesNullsAsEmpty() throws Exception {
        setField("clientId", Optional.of(""));

        String url = provider.getAuthorizationUrl(null, "http://cb");

        assertFalse(url.contains("null"), url);
        assertTrue(url.contains("client_id=&"), url);
        assertTrue(url.endsWith("state="), url);
    }

    @Test
    @DisplayName("the configured redirect URI outranks the one supplied on the request")
    void authorizationUrlUsesTheConfiguredRedirectUri() {
        String url = provider.getAuthorizationUrl("state-1", "http://ignored.example/cb");

        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback"), url);
    }

    @Test
    @DisplayName("a blank configured redirect URI falls back to the one on the request")
    void authorizationUrlFallsBackToTheRequestRedirectWhenConfigIsBlank() throws Exception {
        setField("redirectUriConfig", Optional.of("   "));

        String url = provider.getAuthorizationUrl("state-1", "http://cb.example/from-request");

        assertTrue(url.contains("redirect_uri=http%3A%2F%2Fcb.example%2Ffrom-request"), url);
    }

    @Test
    @DisplayName("special characters in the state are percent-encoded")
    void authorizationUrlEncodesSpecialCharacters() {
        String url = provider.getAuthorizationUrl("a b&c=d", "http://cb");

        assertTrue(url.contains("state=a+b%26c%3Dd"), url);
    }

    // ---------- RS256-only guard ----------

    @Test
    void testAssertRsaSigned() {
        assertDoesNotThrow(() -> OidcIdentityProvider.assertRsaSigned(Map.of("alg", "RS256")));
        assertThrows(IllegalStateException.class, () -> OidcIdentityProvider.assertRsaSigned(Map.of("alg", "HS256")));
        assertThrows(IllegalStateException.class, () -> OidcIdentityProvider.assertRsaSigned(Map.of("alg", "none")));
    }

    @Test
    @DisplayName("assertRsaSigned rejects a header with no algorithm at all")
    void assertRsaSignedRejectsAMissingAlgorithm() {
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> OidcIdentityProvider.assertRsaSigned(Map.of()));
        assertTrue(ex.getMessage().contains("RS256"), ex.getMessage());
    }

    // ---------- PKCE ----------

    @Test
    void testS256Challenge() {
        String challenge = OidcIdentityProvider.s256Challenge("test-verifier-123");
        assertNotNull(challenge);
        assertFalse(challenge.isBlank());
    }

    @Test
    @DisplayName("s256Challenge reproduces the RFC 7636 appendix B vector")
    void s256ChallengeMatchesTheRfcVector() {
        assertEquals(
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OidcIdentityProvider.s256Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    // ---------- JWT payload decoding ----------

    @Test
    @DisplayName("decodeJwtPayload returns the claims segment")
    void decodeJwtPayloadReturnsTheClaims() throws Exception {
        Map<String, Object> payload =
                OidcIdentityProvider.decodeJwtPayload(token(Map.of("alg", "RS256", "kid", "kid-1"), validClaims()));

        assertEquals("user-1", payload.get("sub"));
        assertEquals("user@example.com", payload.get("email"));
    }

    @Test
    @DisplayName("decodeJwtPayload rejects a token that is not three segments")
    void decodeJwtPayloadRejectsAMalformedToken() {
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> OidcIdentityProvider.decodeJwtPayload("only.two"));
        assertTrue(ex.getMessage().contains("Malformed"), ex.getMessage());
    }

    @Test
    @DisplayName("decodeJwtPayload rejects a segment that is not decodable JSON")
    void decodeJwtPayloadRejectsAnUndecodableSegment() {
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> OidcIdentityProvider.decodeJwtPayload("a.!!!.c"));
        assertTrue(ex.getMessage().contains("Failed to decode"), ex.getMessage());
    }

    // ---------- claim validation ----------

    @Test
    void testValidateIdTokenClaimsValid() {
        long future = Instant.now().getEpochSecond() + 3600;
        Map<String, Object> claims = Map.of(
                "exp", future,
                "iss", "https://idp.example.com",
                "aud", "client-123",
                "nonce", "n-1");

        assertDoesNotThrow(() ->
                OidcIdentityProvider.validateIdTokenClaims(claims, "https://idp.example.com", "client-123", "n-1"));
    }

    @Test
    void testValidateIdTokenClaimsAudienceList() {
        long future = Instant.now().getEpochSecond() + 3600;
        Map<String, Object> claims = Map.of(
                "exp",
                future,
                "iss",
                "https://idp.example.com",
                "aud",
                List.of("client-123", "other-client"),
                "nonce",
                "n-1");

        assertDoesNotThrow(() ->
                OidcIdentityProvider.validateIdTokenClaims(claims, "https://idp.example.com", "client-123", "n-1"));
    }

    @Test
    void testValidateIdTokenClaimsExpired() {
        long past = Instant.now().getEpochSecond() - 3600;
        Map<String, Object> claims = Map.of(
                "exp", past,
                "iss", "https://idp.example.com",
                "aud", "client-123");

        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(
                        claims, "https://idp.example.com", "client-123", null));
    }

    @Test
    void testValidateIdTokenClaimsIssuerMismatch() {
        long future = Instant.now().getEpochSecond() + 3600;
        Map<String, Object> claims = Map.of(
                "exp", future,
                "iss", "https://evil.example.com",
                "aud", "client-123");

        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(
                        claims, "https://idp.example.com", "client-123", null));
    }

    @Test
    @DisplayName("validateIdTokenClaims rejects a missing expiration")
    void validateIdTokenClaimsRejectsAMissingExpiration() {
        Map<String, Object> claims = Map.of("iss", ISSUER, "aud", CLIENT_ID);

        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(claims, ISSUER, CLIENT_ID, null));
    }

    @Test
    @DisplayName("validateIdTokenClaims rejects a non-numeric expiration")
    void validateIdTokenClaimsRejectsANonNumericExpiration() {
        Map<String, Object> claims = Map.of("exp", "soon", "iss", ISSUER, "aud", CLIENT_ID);

        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(claims, ISSUER, CLIENT_ID, null));
    }

    @Test
    @DisplayName("validateIdTokenClaims skips the issuer check when none is expected")
    void validateIdTokenClaimsSkipsTheIssuerCheck() {
        long future = Instant.now().getEpochSecond() + 3600;
        Map<String, Object> claims = Map.of("exp", future, "iss", "https://anyone.example.com", "aud", CLIENT_ID);

        assertDoesNotThrow(() -> OidcIdentityProvider.validateIdTokenClaims(claims, null, CLIENT_ID, null));
        assertDoesNotThrow(() -> OidcIdentityProvider.validateIdTokenClaims(claims, "   ", CLIENT_ID, null));
    }

    @Test
    @DisplayName("validateIdTokenClaims rejects a string audience mismatch")
    void validateIdTokenClaimsRejectsAStringAudienceMismatch() {
        long future = Instant.now().getEpochSecond() + 3600;
        Map<String, Object> claims = Map.of("exp", future, "iss", ISSUER, "aud", "some-other-client");

        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(claims, ISSUER, CLIENT_ID, null));
    }

    @Test
    @DisplayName("validateIdTokenClaims rejects an audience that is neither a string nor a list")
    void validateIdTokenClaimsRejectsAnUnusableAudienceType() {
        long future = Instant.now().getEpochSecond() + 3600;
        Map<String, Object> claims = Map.of("exp", future, "iss", ISSUER, "aud", 42);

        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(claims, ISSUER, CLIENT_ID, null));
    }

    @Test
    @DisplayName("validateIdTokenClaims rejects a missing audience")
    void validateIdTokenClaimsRejectsAMissingAudience() {
        long future = Instant.now().getEpochSecond() + 3600;
        Map<String, Object> claims = Map.of("exp", future, "iss", ISSUER);

        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(claims, ISSUER, CLIENT_ID, null));
    }

    @Test
    @DisplayName("validateIdTokenClaims rejects a nonce mismatch")
    void validateIdTokenClaimsRejectsANonceMismatch() {
        long future = Instant.now().getEpochSecond() + 3600;
        Map<String, Object> claims = Map.of("exp", future, "iss", ISSUER, "aud", CLIENT_ID, "nonce", "n-2");

        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(claims, ISSUER, CLIENT_ID, "n-1"));
    }

    @Test
    @DisplayName("validateIdTokenClaims skips the nonce check when none is expected")
    void validateIdTokenClaimsSkipsTheNonceCheck() {
        long future = Instant.now().getEpochSecond() + 3600;
        Map<String, Object> claims = Map.of("exp", future, "iss", ISSUER, "aud", CLIENT_ID);

        assertDoesNotThrow(() -> OidcIdentityProvider.validateIdTokenClaims(claims, ISSUER, CLIENT_ID, null));
    }

    // ---------- authenticate() over a mocked JAX-RS client ----------

    private Client mockHttpClient;

    private void installMockHttpClient() throws Exception {
        mockHttpClient = mock(Client.class);
        setField("httpClient", mockHttpClient);
    }

    /** Stubs {@code client.target(url)}; the returned builder's {@code get}/{@code post} yield {@code payload}. */
    private Invocation.Builder stubEndpoint(String url, Map<String, Object> payload) {
        Invocation.Builder builder = mock(Invocation.Builder.class);
        when(builder.header(anyString(), any())).thenReturn(builder);
        doReturn(payload).when(builder).get(eq(Map.class));
        doReturn(payload).when(builder).post(any(Entity.class), eq(Map.class));
        WebTarget target = mock(WebTarget.class);
        when(target.request(any(MediaType[].class))).thenReturn(builder);
        when(mockHttpClient.target(url)).thenReturn(target);
        return builder;
    }

    private static Form capturedForm(Invocation.Builder builder) {
        ArgumentCaptor<Entity> captor = ArgumentCaptor.forClass(Entity.class);
        verify(builder).post(captor.capture(), eq(Map.class));
        return (Form) captor.getValue().getEntity();
    }

    private static KeyPair newRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String signedIdToken(Map<String, Object> header, Map<String, Object> claims, PrivateKey key)
            throws Exception {
        String signingInput = b64(JSON.writeValueAsBytes(header)) + "." + b64(JSON.writeValueAsBytes(claims));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64(signature.sign());
    }

    private static Map<String, Object> jwks(PublicKey publicKey, String kid) {
        RSAPublicKey rsa = (RSAPublicKey) publicKey;
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("kty", "RSA");
        key.put("use", "sig");
        key.put("kid", kid);
        key.put("n", b64(rsa.getModulus().toByteArray()));
        key.put("e", b64(rsa.getPublicExponent().toByteArray()));
        return Map.of("keys", List.of(key));
    }

    private static Map<String, Object> claimsWith(String name, Object value) {
        Map<String, Object> claims = new LinkedHashMap<>(validClaims());
        claims.put(name, value);
        return claims;
    }

    private static Map<String, Object> claimsWithout(String name) {
        Map<String, Object> claims = new LinkedHashMap<>(validClaims());
        claims.remove(name);
        return claims;
    }

    @Test
    @DisplayName("authenticate returns the profile built from a signature-verified id_token, without touching userinfo")
    void authenticateReturnsTheProfileFromAVerifiedIdToken() throws Exception {
        KeyPair keyPair = newRsaKeyPair();
        installMockHttpClient();
        String idToken =
                signedIdToken(Map.of("alg", "RS256", "kid", KID), claimsWith("nonce", "n-1"), keyPair.getPrivate());
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", idToken));
        Invocation.Builder userinfo = stubEndpoint(USERINFO_URL, Map.of("sub", "should-not-be-used"));
        stubEndpoint(JWKS_URL, jwks(keyPair.getPublic(), KID));

        ExternalUserProfile profile = provider.authenticate("code-1", "http://cb", "verifier-1", "n-1");

        assertEquals("user-1", profile.providerUserId());
        assertEquals("user@example.com", profile.email());
        assertTrue(profile.emailVerified());
        assertEquals("Test User", profile.displayName());
        assertEquals("https://example.com/p.png", profile.avatarUrl());
        verify(userinfo, never()).get(eq(Map.class));
    }

    @Test
    @DisplayName("authenticate falls back to the userinfo endpoint when the token response has no id_token")
    void authenticateFallsBackToUserinfoWhenNoIdTokenIsReturned() throws Exception {
        installMockHttpClient();
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        Invocation.Builder userinfo = stubEndpoint(
                USERINFO_URL,
                Map.of(
                        "sub",
                        "user-9",
                        "email",
                        "u9@example.com",
                        "email_verified",
                        true,
                        "name",
                        "User Nine",
                        "picture",
                        "https://example.com/9.png"));

        ExternalUserProfile profile = provider.authenticate("code-1", "http://cb");

        assertEquals("user-9", profile.providerUserId());
        assertEquals("u9@example.com", profile.email());
        assertTrue(profile.emailVerified());
        assertEquals("User Nine", profile.displayName());
        assertEquals("https://example.com/9.png", profile.avatarUrl());

        ArgumentCaptor<Object> headerValue = ArgumentCaptor.forClass(Object.class);
        verify(userinfo).header(eq("Authorization"), headerValue.capture());
        assertEquals("Bearer at-1", headerValue.getValue());
    }

    @Test
    @DisplayName("a userinfo response without the optional profile fields still yields a usable identity")
    void authenticateToleratesAMinimalUserinfoResponse() throws Exception {
        installMockHttpClient();
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        ExternalUserProfile profile = provider.authenticate("code-1", "http://cb");

        assertEquals("user-9", profile.providerUserId());
        assertFalse(profile.emailVerified());
        assertNull(profile.email());
        assertNull(profile.displayName());
        assertNull(profile.avatarUrl());
    }

    @Test
    @DisplayName("an id_token is not trusted when no JWKS is configured, so authenticate falls back to userinfo")
    void authenticateIgnoresAnIdTokenWhenNoJwksIsConfigured() throws Exception {
        installMockHttpClient();
        String idToken = token(Map.of("alg", "RS256"), validClaims());
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", idToken));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        setField("jwksUrl", Optional.empty());
        assertEquals("user-9", provider.authenticate("code-1", "http://cb").providerUserId());

        setField("jwksUrl", Optional.of("   "));
        assertEquals("user-9", provider.authenticate("code-1", "http://cb").providerUserId());
    }

    @Test
    @DisplayName("a blank id_token is treated as absent")
    void authenticateTreatsABlankIdTokenAsAbsent() throws Exception {
        installMockHttpClient();
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", "   "));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        assertEquals("user-9", provider.authenticate("code-1", "http://cb").providerUserId());
    }

    @Test
    @DisplayName("authenticate rejects a token response with no access_token")
    void authenticateRejectsATokenResponseWithoutAnAccessToken() throws Exception {
        installMockHttpClient();
        stubEndpoint(TOKEN_URL, Map.of("id_token", "irrelevant"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(ex.getMessage().contains("Failed to authenticate"), ex.getMessage());
    }

    @Test
    @DisplayName("authenticate rejects a userinfo response without a subject")
    void authenticateRejectsAUserinfoResponseWithoutASubject() throws Exception {
        installMockHttpClient();
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USERINFO_URL, Map.of("email", "x@example.com"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(
                ex.getCause().getMessage().contains("userinfo"), ex.getCause().getMessage());
    }

    @Test
    @DisplayName("a transport failure surfaces as an IllegalStateException carrying the original cause")
    void authenticateWrapsTransportFailures() throws Exception {
        installMockHttpClient();
        WebTarget target = mock(WebTarget.class);
        when(target.request(any(MediaType[].class))).thenThrow(new ProcessingException("boom"));
        when(mockHttpClient.target(TOKEN_URL)).thenReturn(target);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertInstanceOf(ProcessingException.class, ex.getCause());
    }

    // ---------- token request encoding ----------

    @Test
    @DisplayName("authenticate posts an authorization_code form carrying the PKCE verifier and client secret")
    void authenticatePostsTheAuthorizationCodeForm() throws Exception {
        installMockHttpClient();
        Invocation.Builder tokenEndpoint = stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        provider.authenticate("code-1", "http://cb.example/from-request", "verifier-1", null);

        Form form = capturedForm(tokenEndpoint);
        assertEquals("authorization_code", form.asMap().getFirst("grant_type"));
        assertEquals("code-1", form.asMap().getFirst("code"));
        assertEquals("https://app.example.com/callback", form.asMap().getFirst("redirect_uri"));
        assertEquals(CLIENT_ID, form.asMap().getFirst("client_id"));
        assertEquals("secret-456", form.asMap().getFirst("client_secret"));
        assertEquals("verifier-1", form.asMap().getFirst("code_verifier"));
    }

    @Test
    @DisplayName("a blank PKCE verifier is omitted from the token form")
    void authenticateOmitsABlankCodeVerifier() throws Exception {
        installMockHttpClient();
        Invocation.Builder tokenEndpoint = stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        provider.authenticate("code-1", "http://cb", "   ", null);

        assertFalse(capturedForm(tokenEndpoint).asMap().containsKey("code_verifier"));
    }

    @Test
    @DisplayName("the token form uses the request redirect URI when the configured one is absent")
    void authenticateUsesTheRequestRedirectWhenConfigIsAbsent() throws Exception {
        setField("redirectUriConfig", Optional.empty());
        installMockHttpClient();
        Invocation.Builder tokenEndpoint = stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        provider.authenticate("code-1", "http://cb.example/from-request");

        assertEquals(
                "http://cb.example/from-request",
                capturedForm(tokenEndpoint).asMap().getFirst("redirect_uri"));
    }

    @Test
    @DisplayName("the client_secret parameter is omitted when no secret is configured")
    void authenticateOmitsTheClientSecretWhenNoneIsConfigured() throws Exception {
        setField("clientSecret", Optional.empty());
        installMockHttpClient();
        Invocation.Builder tokenEndpoint = stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        provider.authenticate("code-1", "http://cb");

        assertFalse(capturedForm(tokenEndpoint).asMap().containsKey("client_secret"));
    }

    // ---------- client-secret resolution ----------

    @Test
    @DisplayName("a configured vault key supplies the client secret in preference to the plaintext one")
    void authenticateResolvesTheClientSecretFromTheVault() throws Exception {
        setField("clientSecretVaultKey", Optional.of("oidc/secret"));
        installMockHttpClient();
        when(provider.secretVault.retrieveSystemKey("oidc/secret"))
                .thenAnswer(invocation -> Optional.of(new VaultKey("from-vault".getBytes(StandardCharsets.UTF_8))));
        Invocation.Builder tokenEndpoint = stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        provider.authenticate("code-1", "http://cb");

        assertEquals("from-vault", capturedForm(tokenEndpoint).asMap().getFirst("client_secret"));
    }

    @Test
    @DisplayName("a vault key that resolves to nothing falls back to the plaintext secret")
    void authenticateFallsBackToThePlaintextSecretWhenTheVaultKeyIsMissing() throws Exception {
        setField("clientSecretVaultKey", Optional.of("oidc/secret"));
        installMockHttpClient();
        when(provider.secretVault.retrieveSystemKey("oidc/secret")).thenReturn(Optional.empty());
        Invocation.Builder tokenEndpoint = stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        provider.authenticate("code-1", "http://cb");

        assertEquals("secret-456", capturedForm(tokenEndpoint).asMap().getFirst("client_secret"));
    }

    @Test
    @DisplayName("a vault lookup that throws falls back to the plaintext secret")
    void authenticateFallsBackToThePlaintextSecretWhenTheVaultThrows() throws Exception {
        setField("clientSecretVaultKey", Optional.of("oidc/secret"));
        installMockHttpClient();
        when(provider.secretVault.retrieveSystemKey("oidc/secret"))
                .thenThrow(new IllegalStateException("vault unavailable"));
        Invocation.Builder tokenEndpoint = stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        provider.authenticate("code-1", "http://cb");

        assertEquals("secret-456", capturedForm(tokenEndpoint).asMap().getFirst("client_secret"));
    }

    @Test
    @DisplayName("a blank vault key skips the vault entirely")
    void authenticateSkipsTheVaultWhenTheKeyIsBlank() throws Exception {
        setField("clientSecretVaultKey", Optional.of("   "));
        installMockHttpClient();
        Invocation.Builder tokenEndpoint = stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1"));
        stubEndpoint(USERINFO_URL, Map.of("sub", "user-9"));

        provider.authenticate("code-1", "http://cb");

        assertEquals("secret-456", capturedForm(tokenEndpoint).asMap().getFirst("client_secret"));
        verify(provider.secretVault, never()).retrieveSystemKey(anyString());
    }

    // ---------- id_token verification ----------

    @Test
    @DisplayName("an id_token with a non-RS256 header is refused")
    void authenticateRefusesAnIdTokenThatIsNotRsaSigned() throws Exception {
        KeyPair keyPair = newRsaKeyPair();
        installMockHttpClient();
        String idToken = signedIdToken(Map.of("alg", "HS256", "kid", KID), validClaims(), keyPair.getPrivate());
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", idToken));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(ex.getCause().getMessage().contains("RS256"), ex.getCause().getMessage());
    }

    @Test
    @DisplayName("an id_token signed by a key other than the advertised one is refused")
    void authenticateRefusesAnIdTokenWithABadSignature() throws Exception {
        KeyPair signingKey = newRsaKeyPair();
        KeyPair publishedKey = newRsaKeyPair();
        installMockHttpClient();
        String idToken = signedIdToken(Map.of("alg", "RS256", "kid", KID), validClaims(), signingKey.getPrivate());
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", idToken));
        stubEndpoint(JWKS_URL, jwks(publishedKey.getPublic(), KID));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(
                ex.getCause().getMessage().contains("signature invalid"),
                ex.getCause().getMessage());
    }

    @Test
    @DisplayName("an id_token missing sub is refused even though its signature verifies")
    void authenticateRefusesAnIdTokenWithoutASubject() throws Exception {
        KeyPair keyPair = newRsaKeyPair();
        installMockHttpClient();
        String idToken = signedIdToken(Map.of("alg", "RS256", "kid", KID), claimsWithout("sub"), keyPair.getPrivate());
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", idToken));
        stubEndpoint(JWKS_URL, jwks(keyPair.getPublic(), KID));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(
                ex.getCause().getMessage().contains("missing sub"),
                ex.getCause().getMessage());
    }

    @Test
    @DisplayName("an expired id_token is refused even though its signature verifies")
    void authenticateRefusesAnExpiredIdToken() throws Exception {
        KeyPair keyPair = newRsaKeyPair();
        installMockHttpClient();
        String idToken = signedIdToken(
                Map.of("alg", "RS256", "kid", KID),
                claimsWith("exp", Instant.now().getEpochSecond() - 60),
                keyPair.getPrivate());
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", idToken));
        stubEndpoint(JWKS_URL, jwks(keyPair.getPublic(), KID));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(ex.getCause().getMessage().contains("expired"), ex.getCause().getMessage());
    }

    @Test
    @DisplayName("an id_token that is not three segments is refused")
    void authenticateRefusesAMalformedIdToken() throws Exception {
        installMockHttpClient();
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", "not-a-jwt"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(
                ex.getCause().getMessage().contains("Malformed"), ex.getCause().getMessage());
    }

    @Test
    @DisplayName("an id_token whose payload segment is not decodable JSON is refused")
    void authenticateRefusesAnUndecodableIdTokenPayload() throws Exception {
        installMockHttpClient();
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", "a.!!!.c"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(
                ex.getCause().getMessage().contains("Failed to decode"),
                ex.getCause().getMessage());
    }

    // ---------- JWKS key selection ----------

    @Test
    @DisplayName("findJwksKey falls back to the first RSA signing key when no kid matches")
    void findJwksKeyFallsBackToTheFirstRsaSigningKey() throws Exception {
        KeyPair keyPair = newRsaKeyPair();
        installMockHttpClient();
        String idToken =
                signedIdToken(Map.of("alg", "RS256", "kid", "unknown-kid"), validClaims(), keyPair.getPrivate());
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", idToken));
        stubEndpoint(JWKS_URL, jwks(keyPair.getPublic(), "different-kid"));

        assertEquals("user-1", provider.authenticate("code-1", "http://cb").providerUserId());
    }

    @Test
    @DisplayName("a JWKS whose keys are all unusable yields 'key not found'")
    void authenticateRefusesWhenTheJwksHasNoUsableKey() throws Exception {
        KeyPair keyPair = newRsaKeyPair();
        installMockHttpClient();
        String idToken = signedIdToken(Map.of("alg", "RS256", "kid", KID), validClaims(), keyPair.getPrivate());
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", idToken));
        stubEndpoint(JWKS_URL, Map.of("keys", List.of(Map.of("kty", "EC", "use", "sig", "kid", "other"))));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(
                ex.getCause().getMessage().contains("key not found"),
                ex.getCause().getMessage());
    }

    @Test
    @DisplayName("a JWKS without a keys array yields 'key not found'")
    void authenticateRefusesAJwksWithoutAKeysArray() throws Exception {
        KeyPair keyPair = newRsaKeyPair();
        installMockHttpClient();
        String idToken = signedIdToken(Map.of("alg", "RS256", "kid", KID), validClaims(), keyPair.getPrivate());
        stubEndpoint(TOKEN_URL, Map.of("access_token", "at-1", "id_token", idToken));
        stubEndpoint(JWKS_URL, Map.of("unexpected", "shape"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> provider.authenticate("code-1", "http://cb"));
        assertTrue(
                ex.getCause().getMessage().contains("key not found"),
                ex.getCause().getMessage());
    }

    // ---------- lifecycle ----------

    @Test
    @DisplayName("close() closes the underlying JAX-RS client")
    void closeClosesTheHttpClient() throws Exception {
        installMockHttpClient();

        provider.close();

        verify(mockHttpClient).close();
    }
}
