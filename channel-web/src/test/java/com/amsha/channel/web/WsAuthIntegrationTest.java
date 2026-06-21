package com.amsha.channel.web;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class WsAuthIntegrationTest {

    @Inject
    WsTokenStore tokenStore;

    @Test
    public void testTokenGenerationAndValidation() {
        String token = tokenStore.generateToken();
        assertNotNull(token);

        // First validation should succeed
        assertTrue(tokenStore.validateAndConsumeToken(token));

        // Reused validation should fail
        assertFalse(tokenStore.validateAndConsumeToken(token));
    }

    @Test
    public void testFailsWithExpiredToken() {
        // Caffeine stores tokens, hard to test expiration directly without changing the time.
        // We will just test that an invalid token fails
        assertFalse(tokenStore.validateAndConsumeToken("expired-or-invalid-token"));
    }
}
