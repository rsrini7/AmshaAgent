package com.arcclaw.app.security;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@QuarkusTest
@TestProfile(StartupSecurityValidatorProdSafeTest.Profile.class)
class StartupSecurityValidatorProdSafeTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "arcclaw.auto-seed-secrets", "false",
                "quarkus.profile",                           "test",
                "arcclaw.vault.pepper",                      "safe-pepper-for-boot-000000000000",
                "arcclaw.audit.hmac-key",                    "safe-hmac-key-for-boot-000000000",
                "arcclaw.channel.telegram.allowed-user-ids", "123"
            );
        }
    }

    @Inject StartupSecurityValidator validator;

    @Test
    void testProdWithSafeSecretsDoesNotThrow() {
        // Booted with test profile, but we can unwrap and set to prod safe state
        StartupSecurityValidator real = io.quarkus.arc.ClientProxy.unwrap(validator);
        real.profile = "prod";
        real.pepper = "prod-secure-pepper-0000000000000000";
        real.hmacKey = "prod-secure-hmac-key-0000000000000";

        assertDoesNotThrow(() -> real.onStart(new StartupEvent()));
    }
}
