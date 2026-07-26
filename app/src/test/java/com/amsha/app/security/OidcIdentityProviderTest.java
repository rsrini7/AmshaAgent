package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.security.SecretVault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OidcIdentityProviderTest {

    private OidcIdentityProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new OidcIdentityProvider();
        setField("enabled", true);
        setField("clientId", Optional.of("client-123"));
        setField("clientSecret", Optional.of("secret-456"));
        setField("clientSecretVaultKey", Optional.empty());
        setField("authorizationUrl", Optional.of("https://idp.example.com/auth"));
        setField("tokenUrl", Optional.of("https://idp.example.com/token"));
        setField("userinfoUrl", Optional.of("https://idp.example.com/userinfo"));
        setField("issuer", Optional.of("https://idp.example.com"));
        setField("jwksUrl", Optional.of("https://idp.example.com/jwks"));
        setField("redirectUriConfig", Optional.of("https://app.example.com/callback"));
        provider.secretVault = mock(SecretVault.class);
    }

    private void setField(String name, Object val) throws Exception {
        Field f = OidcIdentityProvider.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(provider, val);
    }

    @Test
    void testBasicProperties() {
        assertEquals("oidc", provider.getProviderId());
        assertTrue(provider.isEnabled());
    }

    @Test
    void testGetAuthorizationUrlDisabledThrows() throws Exception {
        setField("enabled", false);
        assertThrows(IllegalStateException.class, () -> provider.getAuthorizationUrl("state", "http://cb"));
        assertThrows(IllegalStateException.class, () -> provider.authenticate("code", "http://cb"));
    }

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
    void testAssertRsaSigned() {
        assertDoesNotThrow(() -> OidcIdentityProvider.assertRsaSigned(Map.of("alg", "RS256")));
        assertThrows(IllegalStateException.class, () -> OidcIdentityProvider.assertRsaSigned(Map.of("alg", "HS256")));
        assertThrows(IllegalStateException.class, () -> OidcIdentityProvider.assertRsaSigned(Map.of("alg", "none")));
    }

    @Test
    void testS256Challenge() {
        String challenge = OidcIdentityProvider.s256Challenge("test-verifier-123");
        assertNotNull(challenge);
        assertFalse(challenge.isBlank());
    }

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
    void testCloseNoThrow() {
        assertDoesNotThrow(provider::close);
    }
}
