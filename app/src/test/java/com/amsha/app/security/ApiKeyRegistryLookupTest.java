package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(ApiKeyRegistryLookupTest.LookupProfile.class)
class ApiKeyRegistryLookupTest {

    public static class LookupProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.api.keys.admin", "sk-admin-123",
                    "amsha.api.keys.node-1", "sk-node-456",
                    "amsha.audit.hmac-key", "test-secret",
                    "amsha.auth.hmac-key", "test-secret");
        }
    }

    @Inject
    ApiKeyRegistry registry;

    @Test
    void testValidKeyFound() {
        assertTrue(registry.isValidKey("sk-admin-123"));
    }

    @Test
    void testInvalidKeyNotFound() {
        assertFalse(registry.isValidKey("sk-wrong-key"));
    }

    @Test
    void testGetKeyNameForValidKey() {
        assertEquals("admin", registry.getKeyName("sk-admin-123"));
        assertEquals("node-1", registry.getKeyName("sk-node-456"));
    }

    @Test
    void testGetKeyNameForInvalidKey() {
        assertNull(registry.getKeyName("sk-wrong-key"));
    }

    @Test
    void testNullKeyIsHandled() {
        assertFalse(registry.isValidKey(null));
        assertNull(registry.getKeyName(null));
    }
}
