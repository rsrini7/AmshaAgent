package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GithubIdentityProviderTest {

    private GithubIdentityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GithubIdentityProvider();
        provider.clientId = Optional.of("gh-client-123");
        provider.clientSecret = Optional.of("secret");
        provider.redirectUriConfig = Optional.empty();
    }

    @Test
    void testProviderIdAndDisabledState() {
        provider.enabled = false;
        assertEquals("github", provider.getProviderId());
        assertFalse(provider.isEnabled());

        assertThrows(IllegalStateException.class, () -> provider.getAuthorizationUrl("state", "http://callback"));
        assertThrows(IllegalStateException.class, () -> provider.authenticate("code", "http://callback"));
    }

    @Test
    void testGetAuthorizationUrl() {
        provider.enabled = true;
        String url = provider.getAuthorizationUrl("state-123", "http://app.test/callback");
        assertTrue(url.startsWith("https://github.com/login/oauth/authorize?client_id=gh-client-123"));
        assertTrue(url.contains("state=state-123"));
    }
}
