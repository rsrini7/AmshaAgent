package com.arcclaw.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class ApiKeyRegistryTest {

    @Inject
    ApiKeyRegistry registry;


    @BeforeEach
    void setup() {
        // Assume default keys from application.yaml test profile or inject manually if needed.
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
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                if (registry.isAllowed(key)) {
                    successCount.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        assertEquals(20, successCount.get(), "Exactly 20 out of 25 concurrent requests should succeed");
    }
}
