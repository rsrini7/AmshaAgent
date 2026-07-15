package com.amsha.core.security;

/**
 * SPI interface for pluggable external identity providers (e.g. OIDC, GitHub OAuth).
 */
public interface ExternalIdentityProvider {
    /**
     * Unique identifier for this provider (e.g., "google", "github").
     */
    String getProviderId();

    /**
     * Whether this provider is currently enabled and should be advertised to clients or
     * accepted on the login callback. Disabled providers must not appear in {@code /auth/providers}
     * nor start an authorization flow. Defaults to enabled; concrete providers with a config-gated
     * enable flag override this.
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Generates the authorization URL to redirect the user's browser to.
     *
     * @param state anti-forgery state parameter
     * @param redirectUri the registered callback URL
     */
    String getAuthorizationUrl(String state, String redirectUri);

    /**
     * Exchanges the authorization code for a profile.
     *
     * @param code authorization code returned by the provider
     * @param redirectUri the registered callback URL
     */
    ExternalUserProfile authenticate(String code, String redirectUri);

    /**
     * Generates the authorization URL including PKCE {@code code_challenge} and {@code nonce}.
     * Default delegates to {@link #getAuthorizationUrl(String, String)} for providers that do not
     * support these (e.g. GitHub). Providers that support OIDC override this.
     *
     * @param state anti-forgery state parameter
     * @param redirectUri the registered callback URL
     * @param codeChallenge S256 PKCE challenge, or null if unused
     * @param nonce OIDC nonce, or null if unused
     */
    default String getAuthorizationUrl(String state, String redirectUri, String codeChallenge, String nonce) {
        return getAuthorizationUrl(state, redirectUri);
    }

    /**
     * Exchanges the authorization code, supplying the PKCE {@code code_verifier} and {@code nonce}
     * for validation. Default delegates to {@link #authenticate(String, String)} for providers that
     * do not support these. Providers that support OIDC override this.
     *
     * @param code authorization code returned by the provider
     * @param redirectUri the registered callback URL
     * @param codeVerifier PKCE verifier matching the challenge, or null if unused
     * @param nonce OIDC nonce expected in the id_token, or null if unused
     */
    default ExternalUserProfile authenticate(String code, String redirectUri, String codeVerifier, String nonce) {
        return authenticate(code, redirectUri);
    }
}
