package com.arcclaw.app.security;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApiKeyRegistryTest {

    @Inject
    ApiKeyRegistry registry;

    @InjectMock
    Clock clockMock;

    @BeforeEach
    void setup() {
        // Assume default keys from application.yaml test profile or inject manually if needed.
        // For tests, we'll verify behavior using known or mocked keys.
        // Actually since we cannot easily change config properties dynamically without profiles,
        // we'll use reflection or just assume the test profile has no keys by default.
        // Wait, application.yaml test profile doesn't have keys, but default does via env vars with fallbacks.
        // "key-internal-changeme", "key-ci-changeme"
        
        when(clockMock.instant()).thenReturn(Instant.parse("2026-02-24T20:00:00Z"));
    }

    @Test
    void testValidKeys() {
        assertTrue(registry.isValidKey("key-internal-changeme"));
        assertTrue(registry.isValidKey("key-ci-changeme"));
        assertFalse(registry.isValidKey("invalid-key"));
        assertFalse(registry.isValidKey(null));
    }

    @Test
    void testGetKeyName() {
        assertEquals("internal", registry.getKeyName("key-internal-changeme"));
        assertEquals("ci-pipeline", registry.getKeyName("key-ci-changeme"));
        assertNull(registry.getKeyName("invalid"));
        assertNull(registry.getKeyName(null));
    }

    @Test
    void testRateLimiterLimitsAfterThreshold() {
        String key = "test-rate-limit-key";
        
        // Ensure rate limiter starts clean
        assertFalse(registry.isRateLimited(key));

        // Submit 20 requests at the same instant
        for (int i = 0; i < 20; i++) {
            assertEquals(false, registry.isRateLimited(key), "Should not be rate limited before 20 requests. Failed at " + i);
            registry.recordRequest(key);
        }

        // 21st request should be limited
        assertTrue(registry.isRateLimited(key));
    }

    @Test
    void testRateLimiterEvictsOldRequests() {
        String key = "test-evict-key";
        Instant baseTime = Instant.parse("2026-02-24T20:00:00Z");
        
        // Move clock back 61 seconds
        when(clockMock.instant()).thenReturn(baseTime.minusSeconds(61));
        
        // Submit 20 requests and record them 61 seconds ago
        for (int i = 0; i < 20; i++) {
            registry.recordRequest(key);
        }
        
        // Fast forward clock back to now
        when(clockMock.instant()).thenReturn(baseTime);
        
        // Old requests should be evicted, so it shouldn't be limited
        assertFalse(registry.isRateLimited(key));
    }
}
