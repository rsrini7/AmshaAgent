package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.security.Security;
import java.util.List;
import java.util.Optional;

import com.amsha.core.security.SecretVault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.runtime.StartupEvent;

class StartupSecurityValidatorTest {

    private StartupSecurityValidator validator;
    private SecretVault vault;

    @BeforeEach
    void setup() throws Exception {
        validator = new StartupSecurityValidator();
        vault = mock(SecretVault.class);
        setField("vault", vault);
    }

    private void injectFields(String profile, String pepper, boolean hmacKeyExists) throws Exception {
        setField("profile", profile);
        setField("pepper", pepper);
        when(vault.systemKeyExists("amsha-audit-hmac-key")).thenReturn(hmacKeyExists);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = StartupSecurityValidator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(validator, value);
    }

    @Test
    void testProdProfile_devPepper_throws() throws Exception {
        injectFields("prod", "dev-pepper-not-for-production-xxx", true);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> validator.onStart(new StartupEvent()));
        assertEquals(
                "[SECURITY] amsha.vault.pepper is using the dev default. Set AMSHA_VAULT_PEPPER in your .env before running in prod.",
                ex.getMessage());
    }

    @Test
    void testProdProfile_devHmacKey_throws() throws Exception {
        injectFields("prod", "prod-secure-pepper-xxxxxxxxxxxxx", false);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> validator.onStart(new StartupEvent()));
        assertEquals(
                "FATAL: amsha-audit-hmac-key is not present in SecretVault. Provision this key before deploying to production.",
                ex.getMessage());
    }

    @Test
    void testProdProfile_bothDevDefaults_throwsPepperFirst() throws Exception {
        injectFields("prod", "dev-pepper-xxx", false);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> validator.onStart(new StartupEvent()));
        assertEquals(
                "[SECURITY] amsha.vault.pepper is using the dev default. Set AMSHA_VAULT_PEPPER in your .env before running in prod.",
                ex.getMessage());
    }

    @Test
    void testStagingProfile_devPepper_throws() throws Exception {
        injectFields("staging", "dev-pepper-xxx", true);

        assertThrows(IllegalStateException.class, () -> validator.onStart(new StartupEvent()));
    }

    @Test
    void testStagingProfile_devHmacKey_throws() throws Exception {
        injectFields("staging", "valid-staging-pepper-xxxxxxxxxxxx", false);

        assertThrows(IllegalStateException.class, () -> validator.onStart(new StartupEvent()));
    }

    @Test
    void testTestProfile_devPepper_doesNotThrow() throws Exception {
        injectFields("test", "dev-pepper-not-for-production-0000", false);

        assertDoesNotThrow(() -> validator.onStart(new StartupEvent()));
    }

    @Test
    void testDevProfile_devSecrets_doesNotThrow() throws Exception {
        injectFields("dev", "dev-pepper-not-for-production-0000", false);

        assertDoesNotThrow(() -> validator.onStart(new StartupEvent()));
    }

    @Test
    void testProdProfile_validSecrets_doesNotThrow() throws Exception {
        injectFields("prod", "prod-secure-pepper-000000000000000000000000000000", true);
        // Test: inject allowlist fields so the new allowlist check passes
        setField("allowedUserIds", Optional.of(List.of(123L)));
        setField("allowedUsers", Optional.empty());

        assertDoesNotThrow(() -> validator.onStart(new StartupEvent()));
    }

    @Test
    void testNetworkAddressCacheTtlIsEnforced() throws Exception {
        String originalTtl = Security.getProperty("networkaddress.cache.ttl");
        try {
            Security.setProperty("networkaddress.cache.ttl", "");
            injectFields("dev", "dev-pepper-not-for-production-0000", false);
            validator.onStart(new StartupEvent());
            assertEquals("30", Security.getProperty("networkaddress.cache.ttl"));
        } finally {
            if (originalTtl != null) {
                Security.setProperty("networkaddress.cache.ttl", originalTtl);
            } else {
                Security.setProperty("networkaddress.cache.ttl", "");
            }
        }
    }
}
