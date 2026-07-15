package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class OidcIdentityProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String b64url(Object o) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(o));
    }

    private static String idToken(Map<String, Object> claims) throws Exception {
        String header = b64url(Map.of("alg", "RS256", "typ", "JWT"));
        String payload = b64url(claims);
        return header + "." + payload + ".signature";
    }

    @Test
    void s256MatchesRfc7636Vector() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OidcIdentityProvider.s256Challenge(verifier));
    }

    @Test
    void validatesWellFormedClaims() throws Exception {
        Map<String, Object> claims = Map.of(
                "sub", "user-123",
                "iss", "https://issuer.example.com",
                "aud", "my-client-id",
                "nonce", "abc-nonce",
                "exp", Instant.now().plusSeconds(3600).getEpochSecond());
        OidcIdentityProvider.validateIdTokenClaims(
                OidcIdentityProvider.decodeJwtPayload(idToken(claims)),
                "https://issuer.example.com",
                "my-client-id",
                "abc-nonce");
    }

    @Test
    void acceptsAudAsList() throws Exception {
        Map<String, Object> claims = Map.of(
                "sub", "user-123",
                "iss", "https://issuer.example.com",
                "aud", List.of("other", "my-client-id"),
                "nonce", "abc-nonce",
                "exp", Instant.now().plusSeconds(3600).getEpochSecond());
        OidcIdentityProvider.validateIdTokenClaims(
                OidcIdentityProvider.decodeJwtPayload(idToken(claims)),
                "https://issuer.example.com",
                "my-client-id",
                "abc-nonce");
    }

    @Test
    void rejectsIssuerMismatch() throws Exception {
        Map<String, Object> claims = Map.of(
                "sub", "user-123",
                "iss", "https://evil.example.com",
                "aud", "my-client-id",
                "nonce", "abc-nonce",
                "exp", Instant.now().plusSeconds(3600).getEpochSecond());
        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(
                        OidcIdentityProvider.decodeJwtPayload(idToken(claims)),
                        "https://issuer.example.com",
                        "my-client-id",
                        "abc-nonce"));
    }

    @Test
    void rejectsAudienceMismatch() throws Exception {
        Map<String, Object> claims = Map.of(
                "sub", "user-123",
                "iss", "https://issuer.example.com",
                "aud", "wrong-client",
                "nonce", "abc-nonce",
                "exp", Instant.now().plusSeconds(3600).getEpochSecond());
        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(
                        OidcIdentityProvider.decodeJwtPayload(idToken(claims)),
                        "https://issuer.example.com",
                        "my-client-id",
                        "abc-nonce"));
    }

    @Test
    void rejectsNonceMismatch() throws Exception {
        Map<String, Object> claims = Map.of(
                "sub", "user-123",
                "iss", "https://issuer.example.com",
                "aud", "my-client-id",
                "nonce", "different-nonce",
                "exp", Instant.now().plusSeconds(3600).getEpochSecond());
        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(
                        OidcIdentityProvider.decodeJwtPayload(idToken(claims)),
                        "https://issuer.example.com",
                        "my-client-id",
                        "abc-nonce"));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        Map<String, Object> claims = Map.of(
                "sub", "user-123",
                "iss", "https://issuer.example.com",
                "aud", "my-client-id",
                "nonce", "abc-nonce",
                "exp", Instant.now().minusSeconds(3600).getEpochSecond());
        assertThrows(
                IllegalStateException.class,
                () -> OidcIdentityProvider.validateIdTokenClaims(
                        OidcIdentityProvider.decodeJwtPayload(idToken(claims)),
                        "https://issuer.example.com",
                        "my-client-id",
                        "abc-nonce"));
    }

    @Test
    void rejectsNoneAlgIdToken() {
        Map<String, Object> header = Map.of("alg", "none", "typ", "JWT");
        assertThrows(IllegalStateException.class, () -> OidcIdentityProvider.assertRsaSigned(header));
    }

    @Test
    void rejectsSymmetricAlgIdToken() {
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        assertThrows(IllegalStateException.class, () -> OidcIdentityProvider.assertRsaSigned(header));
    }

    @Test
    void rejectsMissingAlgIdToken() {
        Map<String, Object> header = Map.of("typ", "JWT");
        assertThrows(IllegalStateException.class, () -> OidcIdentityProvider.assertRsaSigned(header));
    }

    @Test
    void acceptsRs256IdToken() {
        Map<String, Object> header = Map.of("alg", "RS256", "typ", "JWT");
        OidcIdentityProvider.assertRsaSigned(header);
    }
}
