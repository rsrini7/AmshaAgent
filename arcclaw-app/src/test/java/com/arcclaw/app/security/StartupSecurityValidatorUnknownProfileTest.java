package com.arcclaw.app.security;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import com.arcclaw.core.security.SecretVault;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(StartupSecurityValidatorUnknownProfileTest.Profile.class)
class StartupSecurityValidatorUnknownProfileTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Boot safely — validator passes during Quarkus startup
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
    @InjectMock SecretVault vault;

    @BeforeEach
    void setup() {
        when(vault.systemKeyExists("arcclaw-audit-hmac-key")).thenReturn(true);
    }

    @Test
    void testUnknownProfileTriggersValidation() {
        StartupSecurityValidator real =
            io.quarkus.arc.ClientProxy.unwrap(validator);

        real.profile    = "production";  // typo — not in denylist
        real.pepper     = "dev-pepper-insecure";
        real.allowedUserIds = Optional.of(List.of(123L));
        real.allowedUsers   = Optional.empty();

        assertThrows(IllegalStateException.class,
            () -> real.onStart(new StartupEvent()));
    }
}
