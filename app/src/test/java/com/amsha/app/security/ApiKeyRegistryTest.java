package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                    "amsha.api.keys.user-a", "aaaa-bbbb-cccc-dddd",
                    "amsha.api.keys.user-b", "eeee-ffff-gggg-hhhh",
                    "amsha.audit.hmac-key", "test-hmac-key-for-registry-tests",
                    "amsha.auth.hmac-key", "test-hmac-key-for-registry-tests",
                    "amsha.api.rate-limit.requests-per-minute", "20",
                    // Pin the mutation limit explicitly: the threshold test asserts
                    // blocking after 10 mutations and must not depend on the
                    // production default (raised from 10 to 1000 in P115).
                    "amsha.api.rate-limit.mutation-per-minute", "10");
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
    void testIsMutationAllowedWithNullReturnsFalse() {
        assertFalse(registry.isMutationAllowed(null));
    }

    @Test
    void testIsMutationAllowedLimitsAfterThreshold() {
        String key = "mutation-test-key";
        for (int i = 0; i < 10; i++) {
            assertTrue(registry.isMutationAllowed(key), "Should be allowed before 10 mutations. Failed at " + i);
        }
        assertFalse(registry.isMutationAllowed(key));
    }

    @Test
    void testComputeDigestReturnsHexString() {
        String digest = registry.computeDigest("some-value");
        assertNotNull(digest);
        assertTrue(digest.matches("[0-9a-f]+"), "Digest should be hex: " + digest);
    }

    @Test
    void testComputeDigestReturnsNullForNull() {
        assertNull(registry.computeDigest(null));
    }

    @Test
    void testRegisterAddsNewKey() {
        registry.register("new-user", "new-key-value");
        assertTrue(registry.isValidKey("new-key-value"));
    }

    @Test
    void testUnregisterRemovesKey() {
        registry.register("temp-user", "temp-key");
        assertTrue(registry.isValidKey("temp-key"));
        registry.unregister("temp-key");
        assertFalse(registry.isValidKey("temp-key"));
    }

    @Test
    void testUnregisterByDigestRemovesKey() {
        registry.register("digest-user", "digest-key");
        String digest = registry.computeDigest("digest-key");
        registry.unregisterByDigest(digest);
        assertFalse(registry.isValidKey("digest-key"));
    }

    @Test
    void testUnregisterByDigestWithNullDoesNothing() {
        registry.unregisterByDigest(null);
        // existing keys still work
        assertTrue(registry.isValidKey("aaaa-bbbb-cccc-dddd"));
    }

    @Test
    void testHasRoleForInternalKey() {
        // The internal key from config isn't set, but we can test with a registered key
        registry.register("my-service", "service-key");
        assertTrue(registry.hasRole("service-key", "SERVICE"));
    }

    @Test
    void testHasRoleReturnsFalseForNull() {
        assertFalse(registry.hasRole(null, "OWNER"));
    }

    @Test
    void testIsAllowedWithNullReturnsFalse() {
        assertFalse(registry.isAllowed(null));
    }

    @Test
    void testIsValidKeyWithNullReturnsFalse() {
        assertFalse(registry.isValidKey(null));
    }

    @Test
    void testIsValidKeyWithEmptyReturnsFalse() {
        assertFalse(registry.isValidKey(""));
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

    @Test
    void testRotatedSessionCache() {
        assertFalse(registry.findRotatedSessionId("old-digest-1").isPresent());
        assertFalse(registry.findRotatedSessionId(null).isPresent());

        registry.registerRotatedSession("old-digest-1", "sess-id-100");
        assertTrue(registry.findRotatedSessionId("old-digest-1").isPresent());
        assertEquals(
                "sess-id-100", registry.findRotatedSessionId("old-digest-1").get());

        // Null parameters should be safe no-ops
        registry.registerRotatedSession(null, "sess-id");
        registry.registerRotatedSession("digest", null);
    }

    @Test
    void testHasRoleWithInternalKey() {
        registry.register("internal", "internal-raw-key");
        assertTrue(registry.hasRole("internal-raw-key", "OWNER"));
        assertTrue(registry.hasRole("internal-raw-key", "internal"));
        assertFalse(registry.hasRole("internal-raw-key", "SERVICE"));
    }

    @Test
    void testHasRoleServiceMismatch() {
        registry.register("some-service", "service-key-123");
        assertTrue(registry.hasRole("service-key-123", "SERVICE"));
        assertTrue(registry.hasRole("service-key-123", "some-service"));
        assertFalse(registry.hasRole("service-key-123", "OWNER"));
    }
}
