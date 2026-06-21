package com.arcclaw.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(ApiKeyRegistryTest.TwoKeyProfile.class)
class ApiKeyRegistryTest {

    @Inject
    ApiKeyRegistry registry;

    public static class TwoKeyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "arcclaw.api.keys.user-a", "aaaa-bbbb-cccc-dddd",
                    "arcclaw.api.keys.user-b", "eeee-ffff-gggg-hhhh",
                    "arcclaw.audit.hmac-key", "test-hmac-key-for-registry-tests",
                    "arcclaw.api.rate-limit.requests-per-minute", "20");
        }
    }

    @Test
    void testIsValidKeyConstantTimeWithHmac() {
        assertTrue(registry.isValidKey("aaaa-bbbb-cccc-dddd"));
        assertFalse(registry.isValidKey("zzzz-0000-1111-2222"));
        assertFalse(registry.isValidKey(null));
        assertFalse(registry.isValidKey(""));
    }

    @Test
    void testGetKeyNameReturnsCorrectName() {
        assertEquals("user-a", registry.getKeyName("aaaa-bbbb-cccc-dddd"));
        assertEquals("user-b", registry.getKeyName("eeee-ffff-gggg-hhhh"));
        assertNull(registry.getKeyName("not-in-map"));
    }

    @Test
    void testRateLimiterLimitsAfterThreshold() {
        String key = "test-rate-limit-key";

        // Submit 20 requests at the same instant
        for (int i = 0; i < 20; i++) {
            assertTrue(registry.isAllowed(key), "Should be allowed before 20 requests. Failed at " + i);
        }

        // 21st request should be limited
        assertFalse(registry.isAllowed(key));
    }

    @Test
    void testConcurrentBurstDoesNotExceedLimit() throws InterruptedException {
        String key = "test-concurrent-burst-key";
        int threads = 25;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                        if (registry.isAllowed(key)) {
                            successCount.incrementAndGet();
                        }
                        latch.countDown();
                    })
                    .start();
        }

        latch.await();
        assertEquals(20, successCount.get(), "Exactly 20 out of 25 concurrent requests should succeed");
    }
}
