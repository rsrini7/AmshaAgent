package com.arcclaw.app.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import jakarta.inject.Inject;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.arc.ClientProxy;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(StartupSecurityValidatorDevExemptTest.Profile.class)
class StartupSecurityValidatorDevExemptTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "arcclaw.auto-seed-secrets", "false",
                    "quarkus.profile", "test",
                    "arcclaw.vault.pepper", "safe-pepper-for-boot-000000000000",
                    "arcclaw.audit.hmac-key", "safe-hmac-key-for-boot-000000000",
                    "arcclaw.channel.telegram.allowed-user-ids", "123");
        }
    }

    @Inject
    StartupSecurityValidator validator;

    @Test
    void testDevProfileSkipsValidationEvenWithUnsafeSecrets() {
        StartupSecurityValidator real = ClientProxy.unwrap(validator);
        real.profile = "dev";
        real.pepper = "dev-pepper-insecure"; // Should be skipped in dev

        assertDoesNotThrow(() -> real.onStart(new StartupEvent()));
    }
}
