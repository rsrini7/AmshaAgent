package com.amsha.app.security;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.security.ExternalUserProfile;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultKey;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OidcIdentityProvider implements ExternalIdentityProvider {

    private static final Logger log = Logger.getLogger(OidcIdentityProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @ConfigProperty(name = "amsha.auth.idp.oidc.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "amsha.auth.idp.oidc.client-id")
    Optional<String> clientId;

    @ConfigProperty(name = "amsha.auth.idp.oidc.client-secret")
    Optional<String> clientSecret;

    @ConfigProperty(name = "amsha.auth.idp.oidc.client-secret-vault-key")
    Optional<String> clientSecretVaultKey;

    @ConfigProperty(name = "amsha.auth.idp.oidc.authorization-url")
    Optional<String> authorizationUrl;

    @ConfigProperty(name = "amsha.auth.idp.oidc.token-url")
    Optional<String> tokenUrl;

    @ConfigProperty(name = "amsha.auth.idp.oidc.userinfo-url")
    Optional<String> userinfoUrl;

    @ConfigProperty(name = "amsha.auth.idp.oidc.issuer")
    Optional<String> issuer;

    @ConfigProperty(name = "amsha.auth.idp.oidc.jwks-url")
    Optional<String> jwksUrl;

    @ConfigProperty(name = "amsha.auth.idp.oidc.redirect-uri")
    Optional<String> redirectUriConfig;

    @Inject
    SecretVault secretVault;

    private final Client httpClient = ClientBuilder.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    @PreDestroy
    void close() {
        httpClient.close();
    }

    @Override
    public String getProviderId() {
        return "oidc";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String getAuthorizationUrl(String state, String redirectUri) {
        return getAuthorizationUrl(state, redirectUri, null, null);
    }

    @Override
    public String getAuthorizationUrl(String state, String redirectUri, String codeChallenge, String nonce) {
        if (!enabled) {
            throw new IllegalStateException("OIDC identity provider is disabled");
        }
        StringBuilder url = new StringBuilder(authorizationUrl.orElse(""))
                .append("?client_id=")
                .append(encode(clientId.orElse("")))
                .append("&redirect_uri=")
                .append(encode(resolveRedirectUri(redirectUri)))
                .append("&response_type=code")
                .append("&scope=openid+email+profile")
                .append("&state=")
                .append(encode(state));
        if (codeChallenge != null && !codeChallenge.isBlank()) {
            url.append("&code_challenge=").append(encode(codeChallenge)).append("&code_challenge_method=S256");
        }
        if (nonce != null && !nonce.isBlank()) {
            url.append("&nonce=").append(encode(nonce));
        }
        return url.toString();
    }

    @Override
    public ExternalUserProfile authenticate(String code, String redirectUri) {
        return authenticate(code, redirectUri, null, null);
    }

    @Override
    public ExternalUserProfile authenticate(String code, String redirectUri, String codeVerifier, String nonce) {
        if (!enabled) {
            throw new IllegalStateException("OIDC identity provider is disabled");
        }

        try {
            Form form = new Form()
                    .param("grant_type", "authorization_code")
                    .param("code", code)
                    .param("redirect_uri", resolveRedirectUri(redirectUri))
                    .param("client_id", clientId.orElse(""));
            String secret = resolveClientSecret();
            if (!secret.isBlank()) {
                form.param("client_secret", secret);
            }
            if (codeVerifier != null && !codeVerifier.isBlank()) {
                form.param("code_verifier", codeVerifier);
            }

            Map<?, ?> tokenResponse = httpClient
                    .target(tokenUrl.orElse(""))
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.form(form), Map.class);

            String accessToken = (String) tokenResponse.get("access_token");
            if (accessToken == null) {
                throw new IllegalStateException("Access token missing from OIDC token response");
            }

            // Prefer validated id_token claims; fall back to userinfo when no id_token is returned.
            ExternalUserProfile fromIdToken = validateAndExtractIdToken(tokenResponse, nonce);
            if (fromIdToken != null) {
                return fromIdToken;
            }

            Map<?, ?> userInfo = httpClient
                    .target(userinfoUrl.orElse(""))
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .header("Authorization", "Bearer " + accessToken)
                    .get(Map.class);

            String sub = (String) userInfo.get("sub");
            if (sub == null) {
                throw new IllegalStateException("Subject (sub) missing from OIDC userinfo response");
            }
            return new ExternalUserProfile(sub, (String) userInfo.get("email"), (String) userInfo.get("name"), (String)
                    userInfo.get("picture"));

        } catch (Exception e) {
            log.error("OIDC authentication failed: " + e.getMessage(), e);
            throw new IllegalStateException("Failed to authenticate with OIDC provider", e);
        }
    }

    private String resolveRedirectUri(String requestRedirectUri) {
        return redirectUriConfig.isPresent() && !redirectUriConfig.get().isBlank()
                ? redirectUriConfig.get()
                : requestRedirectUri;
    }

    private String resolveClientSecret() {
        if (clientSecretVaultKey.isPresent() && !clientSecretVaultKey.get().isBlank()) {
            try {
                Optional<VaultKey> vk = secretVault.retrieveSystemKey(clientSecretVaultKey.get());
                if (vk.isPresent()) {
                    try (VaultKey k = vk.get()) {
                        return new String(k.get(), StandardCharsets.UTF_8);
                    }
                }
                log.warn("OIDC: vault key '" + clientSecretVaultKey.get()
                        + "' not found; falling back to plaintext client-secret");
            } catch (Exception e) {
                log.warn("OIDC: failed to resolve client secret from vault: " + e.getMessage());
            }
        }
        return clientSecret.orElse("");
    }

    private ExternalUserProfile validateAndExtractIdToken(Map<?, ?> tokenResponse, String nonce) {
        String idToken = (String) tokenResponse.get("id_token");
        if (idToken == null || idToken.isBlank()) {
            return null;
        }
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new IllegalStateException("Malformed OIDC id_token");
        }
        Map<String, Object> header = decodeJson(parts[0]);
        Map<String, Object> claims = decodeJson(parts[1]);

        if (jwksUrl.isPresent() && !jwksUrl.get().isBlank()) {
            verifyIdTokenSignature(header, idToken);
        } else {
            // Without a JWKS we cannot verify the id_token signature, so the claims cannot be
            // trusted. Refuse to use them and fall back to the userinfo endpoint (fetched over an
            // authenticated TLS channel with the access token) instead.
            return null;
        }

        validateIdTokenClaims(claims, issuer.orElse(null), clientId.orElse(""), nonce);

        String sub = (String) claims.get("sub");
        if (sub == null) {
            throw new IllegalStateException("OIDC id_token missing sub");
        }
        return new ExternalUserProfile(
                sub, (String) claims.get("email"), (String) claims.get("name"), (String) claims.get("picture"));
    }

    private void verifyIdTokenSignature(Map<String, Object> header, String idToken) {
        assertRsaSigned(header);
        try {
            Map<String, Object> jwks = fetchJwks();
            Map<String, Object> key = findJwksKey(jwks, (String) header.get("kid"));
            if (key == null) {
                throw new IllegalStateException("OIDC JWKS key not found for kid=" + header.get("kid"));
            }
            byte[] n = Base64.getUrlDecoder().decode((String) key.get("n"));
            byte[] e = Base64.getUrlDecoder().decode((String) key.get("e"));
            var publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
            String signingInput = idToken.substring(0, idToken.lastIndexOf('.'));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] signature = Base64.getUrlDecoder().decode(idToken.split("\\.")[2]);
            if (!sig.verify(signature)) {
                throw new IllegalStateException("OIDC id_token signature invalid");
            }
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception e) {
            throw new IllegalStateException("OIDC id_token signature verification failed", e);
        }
    }

    /**
     * Enforces that an id_token uses asymmetric RS256 verification against the configured JWKS.
     * Anything else — notably the {@code none} algorithm or a symmetric {@code HS*} family — allows
     * signature forgery and is rejected. Exposed for testing (no network / CDI required).
     */
    static void assertRsaSigned(Map<String, Object> header) {
        String alg = (String) header.get("alg");
        if (alg == null || !"RS256".equals(alg)) {
            throw new IllegalStateException("OIDC id_token must be signed with RS256; refusing alg='" + alg + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJwks() {
        return httpClient
                .target(jwksUrl.orElse(""))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findJwksKey(Map<String, Object> jwks, String kid) {
        Object keysObj = jwks.get("keys");
        if (!(keysObj instanceof List<?> keys)) {
            return null;
        }
        for (Object k : keys) {
            if (k instanceof Map<?, ?> m && kid != null && kid.equals(m.get("kid"))) {
                return (Map<String, Object>) m;
            }
        }
        // Fall back to the first RSA signing key when no kid match
        for (Object k : keys) {
            if (k instanceof Map<?, ?> m && "RSA".equals(m.get("kty")) && "sig".equals(m.get("use"))) {
                return (Map<String, Object>) m;
            }
        }
        return null;
    }

    private static Map<String, Object> decodeJson(String b64) {
        try {
            return JSON.readValue(Base64.getUrlDecoder().decode(b64), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode OIDC JWT segment", e);
        }
    }

    /**
     * Computes the PKCE S256 code challenge for a given verifier. Exposed for testing.
     */
    static String s256Challenge(String codeVerifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Decodes the claims (payload) segment of an id_token. Exposed for testing.
     */
    static Map<String, Object> decodeJwtPayload(String idToken) {
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new IllegalStateException("Malformed OIDC id_token");
        }
        return decodeJson(parts[1]);
    }

    /**
     * Validates the standard id_token claims (exp, iss, aud, nonce). When {@code expectedIssuer}
     * is null the issuer check is skipped. Exposed for testing (no network / CDI required).
     */
    static void validateIdTokenClaims(
            Map<String, Object> claims, String expectedIssuer, String clientId, String expectedNonce) {
        Object expObj = claims.get("exp");
        if (expObj instanceof Number exp && Instant.now().getEpochSecond() > exp.longValue()) {
            throw new IllegalStateException("OIDC id_token expired");
        }
        if (expectedIssuer != null && !expectedIssuer.isBlank()) {
            Object iss = claims.get("iss");
            if (!expectedIssuer.equals(iss)) {
                throw new IllegalStateException("OIDC id_token issuer mismatch");
            }
        }
        Object aud = claims.get("aud");
        boolean audOk = false;
        if (aud instanceof String a) {
            audOk = a.equals(clientId);
        } else if (aud instanceof List<?> list) {
            audOk = list.stream().anyMatch(x -> x instanceof String s && s.equals(clientId));
        }
        if (!audOk) {
            throw new IllegalStateException("OIDC id_token audience mismatch");
        }
        if (expectedNonce != null) {
            Object n = claims.get("nonce");
            if (!expectedNonce.equals(n)) {
                throw new IllegalStateException("OIDC id_token nonce mismatch");
            }
        }
    }
}
