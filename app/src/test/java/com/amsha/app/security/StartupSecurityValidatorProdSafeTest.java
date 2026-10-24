package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;

import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.arc.ClientProxy;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(StartupSecurityValidatorProdSafeTest.Profile.class)
class StartupSecurityValidatorProdSafeTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.auto-seed-secrets", "false",
                    "quarkus.profile", "test",
                    "amsha.vault.pepper", "safe-pepper-for-boot-000000000000",
                    "amsha.audit.hmac-key", "safe-hmac-key-for-boot-000000000",
                    "amsha.channel.telegram.allowed-user-ids", "123");
        }
    }

    @Inject
    StartupSecurityValidator validator;

    @InjectMock
    SecretVault vault;

    @BeforeEach
    void setup() {
        when(vault.retrieveSystemKey(VaultConstants.AUDIT_HMAC_KEY_NAME))
                .thenReturn(Optional.of(new VaultKey(new byte[32])));
    }

    @Test
    void testProdWithSafeSecretsDoesNotThrow() {
        // Booted with test profile, but we can unwrap and set to prod safe state
        StartupSecurityValidator real = ClientProxy.unwrap(validator);
        real.profile = "prod";
        real.pepper = "prod-secure-pepper-0000000000000000";

        assertDoesNotThrow(() -> real.onStart(new StartupEvent()));
    }
}
