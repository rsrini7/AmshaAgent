package com.arcclaw.app.security;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(StartupSecurityValidatorProdUnsafeTest.Profile.class)
class StartupSecurityValidatorProdUnsafeTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Boot safely — validator passes during Quarkus startup
            return Map.of(
                "quarkus.profile",                           "test",
                "arcclaw.vault.pepper",                      "safe-pepper-for-boot-000000000000",
                "arcclaw.audit.hmac-key",                    "safe-hmac-key-for-boot-000000000",
                "arcclaw.channel.telegram.allowed-user-ids", "123"
            );
        }
    }

    @Inject StartupSecurityValidator validator;

    @Test
    void testProdWithUnsafePepperThrows() {
        // Unwrap CDI proxy to get real instance
        StartupSecurityValidator real =
            io.quarkus.arc.ClientProxy.unwrap(validator);

        // Override fields to simulate unsafe prod config
        real.profile = "prod";
        real.pepper  = "dev-pepper-insecure";
        real.hmacKey = "prod-secure-hmac-key-0000000000000";
        real.allowedUserIds = Optional.of(List.of(123L));
        real.allowedUsers   = Optional.empty();

        assertThrows(IllegalStateException.class,
            () -> real.onStart(new StartupEvent()));
    }
}
