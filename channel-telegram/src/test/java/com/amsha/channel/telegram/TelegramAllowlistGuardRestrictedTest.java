package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(TelegramAllowlistGuardRestrictedTest.RestrictedProfile.class)
class TelegramAllowlistGuardRestrictedTest {

    public static class RestrictedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.auto-seed-secrets", "false",
                    "quarkus.profile", "test",
                    "amsha.vault.pepper", "safe-pepper-for-boot-000000000000",
                    "amsha.audit.hmac-key", "safe-hmac-key-for-boot-000000000",
                    "amsha.channel.telegram.allowed-users", "Alice,@Bob,charlie",
                    "amsha.channel.telegram.allowed-user-ids", "");
        }
    }

    @Inject
    TelegramAllowlistGuard guard;

    @Test
    void testRestrictedAllowlist() {
        // Valid users
        assertTrue(guard.isAllowed(null, "Alice"));
        assertTrue(guard.isAllowed(null, "@Alice")); // user provides leading @, should still match Alice
        assertTrue(guard.isAllowed(null, "Bob")); // configured with @, user provides without
        assertTrue(guard.isAllowed(null, "@Bob")); // configured with @, user provides with @
        assertTrue(guard.isAllowed(null, "CHARLIE")); // case insensitive

        // Invalid users
        assertFalse(guard.isAllowed(null, "eve"));
        assertFalse(guard.isAllowed(null, null));
        assertFalse(guard.isAllowed(null, ""));
    }
}
