package com.arcclaw.app.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(TelegramAllowlistGuardRestrictedTest.RestrictedProfile.class)
class TelegramAllowlistGuardRestrictedTest {

    public static class RestrictedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("arcclaw.telegram.allowed-users", "Alice,@Bob,charlie");
        }
    }

    @Inject
    TelegramAllowlistGuard guard;

    @Test
    void testRestrictedAllowlist() {
        // Valid users
        assertTrue(guard.isAllowed("Alice"));
        assertTrue(guard.isAllowed("@Alice")); // user provides leading @, should still match Alice
        assertTrue(guard.isAllowed("Bob"));    // configured with @, user provides without
        assertTrue(guard.isAllowed("@Bob"));   // configured with @, user provides with @
        assertTrue(guard.isAllowed("CHARLIE")); // case insensitive
        
        // Invalid users
        assertFalse(guard.isAllowed("eve"));
        assertFalse(guard.isAllowed(null));
        assertFalse(guard.isAllowed(""));
    }
}
