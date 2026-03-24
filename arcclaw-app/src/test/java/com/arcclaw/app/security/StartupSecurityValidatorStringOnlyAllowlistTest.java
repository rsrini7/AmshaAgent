package com.arcclaw.app.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;

import com.arcclaw.core.security.SecretVault;
import io.quarkus.arc.ClientProxy;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(StartupSecurityValidatorStringOnlyAllowlistTest.Profile.class)
class StartupSecurityValidatorStringOnlyAllowlistTest {

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
    void testStringOnlyAllowlistInStagingThrowsAtStartup() {
        StartupSecurityValidator real = ClientProxy.unwrap(validator);
        real.profile        = "staging";
        real.pepper         = "staging-secure-pepper-00000000000000";
        real.allowedUserIds = Optional.empty();
        real.allowedUsers   = Optional.of(List.of("alice"));

        assertThrows(DeploymentException.class,
            () -> real.onStart(new StartupEvent()));
    }

    @Test
    void testExceptionMessageMentionsNumericIds() {
        StartupSecurityValidator real = ClientProxy.unwrap(validator);
        real.profile        = "staging";
        real.pepper         = "staging-secure-pepper-00000000000000";
        real.allowedUserIds = Optional.empty();
        real.allowedUsers   = Optional.of(List.of("alice"));

        var ex = assertThrows(DeploymentException.class,
            () -> real.onStart(new StartupEvent()));
        assertTrue(ex.getMessage().contains("numeric"),
            "Exception message must reference 'numeric' Telegram user IDs");
    }
}
