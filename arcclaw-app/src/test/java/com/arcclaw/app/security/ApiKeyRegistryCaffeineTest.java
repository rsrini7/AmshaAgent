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
class ApiKeyRegistryCaffeineTest {

    @Inject
    ApiKeyRegistry registry;

    @InjectMock
    Clock clockMock;

    @BeforeEach
    void setup() {
        when(clockMock.instant()).thenReturn(Instant.parse("2026-02-26T10:00:00Z"));
    }

    @Test
    void testCaffeineRateLimiter_doesNotLeakAcrossKeys() {
        String internalKey = "key-internal-changeme";
        String ciKey = "key-ci-changeme";

        // Record 20 requests for internal key
        for (int i = 0; i < 20; i++) {
            registry.recordRequest(internalKey);
        }

        // Check internal key is limited
        assertTrue(registry.isRateLimited(internalKey));

        // Check CI key is NOT limited (isolation)
        assertFalse(registry.isRateLimited(ciKey));
    }

    @Test
    void testCaffeineRateLimiter_separateRecordAndCheck() {
        String key = "key-internal-changeme";
        Instant baseTime = Instant.parse("2026-02-26T10:00:00Z");

        // Record 20 requests
        for (int i = 0; i < 20; i++) {
            registry.recordRequest(key);
        }
        assertTrue(registry.isRateLimited(key));

        // Advance clock by 61 seconds
        when(clockMock.instant()).thenReturn(baseTime.plusSeconds(61));

        // Should no longer be rate limited as all timestamps are before windowStart
        assertFalse(registry.isRateLimited(key));
    }

    @Test
    void testCaffeineRateLimiter_nullKeyNeverThrows() {
        assertDoesNotThrow(() -> registry.recordRequest(null));
        assertFalse(registry.isRateLimited(null));
    }

    @Test
    void testRateLimiter_exactBoundary_20requestsAllowed_21stBlocked() {
        String key = "key-internal-changeme";
        
        // Loop 20 times: assertFalse(isRateLimited(key)) then recordRequest(key)
        for (int i = 0; i < 20; i++) {
            assertFalse(registry.isRateLimited(key), "Should not be rate limited at request " + (i + 1));
            registry.recordRequest(key);
        }

        // 21st call: assertTrue(isRateLimited(key))
        assertTrue(registry.isRateLimited(key));
    }
}
