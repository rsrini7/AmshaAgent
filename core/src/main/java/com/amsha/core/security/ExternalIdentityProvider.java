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
}
