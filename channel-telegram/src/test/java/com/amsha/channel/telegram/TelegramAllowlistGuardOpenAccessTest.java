package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(TelegramAllowlistGuardOpenAccessTest.Profile.class)
class TelegramAllowlistGuardOpenAccessTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.auto-seed-secrets", "false",
                    "quarkus.profile", "test",
                    "amsha.vault.pepper", "safe-pepper-for-boot-000000000000",
                    "amsha.audit.hmac-key", "safe-hmac-key-for-boot-000000000",
                    "amsha.channel.telegram.allowed-user-ids", "", // ← explicitly clear
                    "amsha.channel.telegram.allowed-users", "" // ← explicitly clear
                    );
        }
    }

    @Inject
    TelegramAllowlistGuard guard;

    @Test
    void testAllowlistOpenAccess() {
        // In the default application.yaml test profile, allowed-users is "" (empty)
        // This signifies open access.
        assertTrue(guard.isAllowed(null, "anyuser"));
        assertTrue(guard.isAllowed(null, null));
        assertTrue(guard.isAllowed(null, ""));
    }
}
