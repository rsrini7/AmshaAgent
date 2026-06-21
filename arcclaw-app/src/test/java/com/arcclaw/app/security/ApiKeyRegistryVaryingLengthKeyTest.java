package com.arcclaw.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(ApiKeyRegistryVaryingLengthKeyTest.VaryingLengthKeyProfile.class)
class ApiKeyRegistryVaryingLengthKeyTest {

    @Inject
    ApiKeyRegistry registry;

    public static class VaryingLengthKeyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "arcclaw.api.keys.k1", "ab",
                    "arcclaw.api.keys.k2", "abcdef012345",
                    "arcclaw.audit.hmac-key", "test-hmac-key-for-registry-tests");
        }
    }

    @Test
    void testGetKeyNameWithDifferentLengthKeysNoCrash() {
        assertEquals("k1", registry.getKeyName("ab"));
        assertEquals("k2", registry.getKeyName("abcdef012345"));
    }
}
