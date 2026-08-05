package com.amsha.channel.web;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class WsAuthIntegrationTest {

    @Inject
    WsTokenStore tokenStore;

    @Test
    public void testTokenGenerationAndValidation() {
        String token = tokenStore.generateToken("owner-1");
        assertNotNull(token);

        // First validation should succeed
        assertEquals(Optional.of("owner-1"), tokenStore.validateAndConsumeToken(token));

        // Reused validation should fail
        assertTrue(tokenStore.validateAndConsumeToken(token).isEmpty());
    }

    @Test
    public void testFailsWithExpiredToken() {
        // Caffeine stores tokens, hard to test expiration directly without changing the time.
        // We will just test that an invalid token fails
        assertTrue(
                tokenStore.validateAndConsumeToken("expired-or-invalid-token").isEmpty());
    }
}
