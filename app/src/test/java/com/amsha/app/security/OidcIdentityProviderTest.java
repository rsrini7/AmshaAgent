package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.security.api.SecretVault;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OidcIdentityProvider}.
 *
 * <p>Covers the surface that is reachable without the Quarkus container: provider identity, authorization-URL
 * construction (including PKCE, nonce, redirect resolution and percent-encoding), the RS256-only algorithm guard,
 * PKCE S256 derivation, JWT payload decoding, and every arm of id_token claim validation.
 *
 * <p><strong>Why {@code authenticate} is not covered here.</strong> It drives a JAX-RS
 * {@code ClientBuilder.newBuilder()…build()} client, and outside the Quarkus runtime that client has no
 * {@code MessageBodyReader} for {@code Map} + {@code application/json}, so every request fails before any of the
 * provider's logic runs:
 *
 * <pre>
 * Response could not be mapped to type interface java.util.Map for response with media type application/json.
 * Hints: Consider adding one the 'quarkus-rest-client-jackson' or 'quarkus-rest-client-jsonb' extensions
 * </pre>
 *
 * The provider is built from a real local {@code HttpServer} acting as the IdP, with genuinely RS256-signed JWTs,
 * and all of it fails on that mapping step — the form encoding, the JWKS lookup and the RSA verification are never
 * reached. Closing this needs a {@code @QuarkusTest} plus a stub-IdP {@code QuarkusTestResourceLifecycleManager};
 * no such test resource exists anywhere in this module, and the sibling {@code GithubIdentityProviderTest} leaves
 * its own HTTP path dark for the same reason. Recorded as a finding rather than worked around by mocking the
 * client (the field is {@code private final}, so replacing it would need reflection into a final field — and the
 * point of the test is the real request/verify path, not a stub of it).
 */
class OidcIdentityProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "https://idp.example.com";
    private static final String CLIENT_ID = "client-123";

    private OidcIdentityProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new OidcIdentityProvider();
        setField("enabled", true);
        setField("clientId", Optional.of(CLIENT_ID));
        setField("clientSecret", Optional.of("secret-456"));
        setField("clientSecretVaultKey", Optional.empty());
        setField("authorizationUrl", Optional.of("https://idp.example.com/auth"));
        setField("tokenUrl", Optional.of("https://idp.example.com/token"));
        setField("userinfoUrl", Optional.of("https://idp.example.com/userinfo"));
        setField("issuer", Optional.of(ISSUER));
        setField("jwksUrl", Optional.of("https://idp.example.com/jwks"));
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
}
