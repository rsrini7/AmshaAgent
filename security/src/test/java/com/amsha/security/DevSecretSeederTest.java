package com.amsha.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Base64;
import java.util.Optional;

import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.runtime.StartupEvent;

/**
 * Unit tests for the dev/prod secret seeder moved from core (S7): profile gating,
 * ephemeral vs env-sourced audit HMAC keys, and idempotent tool-secret seeding.
 */
@ExtendWith(MockitoExtension.class)
class DevSecretSeederTest {

    @Mock
    SecretVault secretVault;

    @Mock
    Config config;

    @Captor
    ArgumentCaptor<byte[]> keyCaptor;

    private DevSecretSeeder seeder;
    private MockedStatic<ConfigProvider> configProvider;

    @BeforeEach
    void setUp() throws Exception {
        seeder = new DevSecretSeeder();
        setField("secretVault", secretVault);
        setField("log", Logger.getLogger("DevSecretSeederTest"));
        lenient().when(config.getOptionalValue(anyString(), eq(String.class))).thenReturn(Optional.empty());
        configProvider = mockStatic(ConfigProvider.class);
        configProvider.when(ConfigProvider::getConfig).thenReturn(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        configProvider.close();
    }

    private void setField(String name, Object value) throws Exception {
        var f = DevSecretSeeder.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(seeder, value);
    }

    private void configure(boolean autoSeed, String profile) throws Exception {
        setField("autoSeedEnabled", autoSeed);
        setField("profile", profile);
    }

    @Test
    void devProfileSeedsEphemeralHmacKeyAndZeroesIt() throws Exception {
        configure(false, "dev");
        when(secretVault.systemKeyExists(VaultConstants.AUDIT_HMAC_KEY_NAME)).thenReturn(false);

        seeder.onStart(new StartupEvent());

        verify(secretVault).storeSystemKey(eq(VaultConstants.AUDIT_HMAC_KEY_NAME), keyCaptor.capture());
        byte[] stored = keyCaptor.getValue();
        assertEquals(32, stored.length);
        // The buffer is zeroed in a finally block after storing; the captor shares the reference.
        assertArrayEquals(new byte[32], stored, "key material must be zeroed after seeding");
    }

    @Test
    void existingDecodableHmacKeyIsLeftAlone() throws Exception {
        configure(false, "dev");
        when(secretVault.systemKeyExists(VaultConstants.AUDIT_HMAC_KEY_NAME)).thenReturn(true);
        when(secretVault.retrieveSystemKey(VaultConstants.AUDIT_HMAC_KEY_NAME))
                .thenReturn(Optional.of(new VaultKey(new byte[] {7})));

        seeder.onStart(new StartupEvent());

        verify(secretVault, never()).storeSystemKey(anyString(), any());
    }

    @Test
    void undecodableExistingHmacKeyTriggersReseed() throws Exception {
        configure(false, "dev");
        when(secretVault.systemKeyExists(VaultConstants.AUDIT_HMAC_KEY_NAME)).thenReturn(true);
        when(secretVault.retrieveSystemKey(VaultConstants.AUDIT_HMAC_KEY_NAME))
                .thenThrow(new IllegalStateException("corrupt"));

        seeder.onStart(new StartupEvent());

        verify(secretVault).storeSystemKey(eq(VaultConstants.AUDIT_HMAC_KEY_NAME), any());
    }

    @Test
    void prodWithoutAutoSeedNeverTouchesTheVault() throws Exception {
        configure(false, "prod");

        seeder.onStart(new StartupEvent());

        verify(secretVault, never()).systemKeyExists(anyString());
        verify(secretVault, never()).storeSystemKey(anyString(), any());
    }

    @Test
    void prodWithAutoSeedStoresBase64AuditKeyAndZeroesIt() throws Exception {
        configure(true, "prod");
        when(secretVault.systemKeyExists(VaultConstants.AUDIT_HMAC_KEY_NAME)).thenReturn(false);
        when(config.getOptionalValue(eq("AMSHA_AUDIT_HMAC_KEY"), eq(String.class)))
                .thenReturn(Optional.of(Base64.getEncoder().encodeToString(new byte[32])));

        seeder.onStart(new StartupEvent());

        verify(secretVault).storeSystemKey(eq(VaultConstants.AUDIT_HMAC_KEY_NAME), keyCaptor.capture());
        assertArrayEquals(new byte[32], keyCaptor.getValue(), "env key must be zeroed after storing");
    }

    @Test
    void prodWithInvalidBase64AuditKeyDoesNotStore() throws Exception {
        configure(true, "prod");
        when(secretVault.systemKeyExists(VaultConstants.AUDIT_HMAC_KEY_NAME)).thenReturn(false);
        when(config.getOptionalValue(eq("AMSHA_AUDIT_HMAC_KEY"), eq(String.class)))
                .thenReturn(Optional.of("not-base64!!!"));

        seeder.onStart(new StartupEvent());

        verify(secretVault, never()).storeSystemKey(anyString(), any());
    }

    @Test
    void seedDevSecretsIsNoOpWhenAutoSeedDisabled() throws Exception {
        configure(false, "dev");

        seeder.seedDevSecrets();

        verifyNoInteractions(secretVault);
    }

    @Test
    void seedDevSecretsStoresConfiguredToolTokens() throws Exception {
        configure(true, "dev");
        when(secretVault.systemKeyExists(anyString())).thenReturn(false);
        when(config.getOptionalValue(eq("AMSHA_TOOLS_GITHUB_TOKEN"), eq(String.class)))
                .thenReturn(Optional.of("gh-token"));

        seeder.seedDevSecrets();

        verify(secretVault).storeSystemKey(eq("AMSHA_TOOLS_GITHUB_TOKEN"), any());
        verify(secretVault, never()).storeSystemKey(eq("AMSHA_TOOLS_SERPER_API_KEY"), any());
        verify(secretVault, never()).storeSystemKey(eq("AMSHA_TOOLS_BRAVE_API_KEY"), any());
    }

    @Test
    void seedDevSecretsSkipsKeysAlreadyPresent() throws Exception {
        configure(true, "dev");
        when(secretVault.systemKeyExists(anyString())).thenReturn(true);

        seeder.seedDevSecrets();

        verify(secretVault, never()).storeSystemKey(anyString(), any());
    }

    @Test
    void vaultStoreFailureIsSwallowedDuringStartupSeeding() throws Exception {
        configure(true, "dev");
        when(secretVault.systemKeyExists("AMSHA_TOOLS_GITHUB_TOKEN")).thenReturn(false);
        when(config.getOptionalValue(eq("AMSHA_TOOLS_GITHUB_TOKEN"), eq(String.class)))
                .thenReturn(Optional.of("gh-token"));
        doThrow(new IllegalStateException("db closed"))
                .when(secretVault)
                .storeSystemKey(eq("AMSHA_TOOLS_GITHUB_TOKEN"), any());

        assertDoesNotThrow(() -> seeder.seedDevSecrets());
        verify(secretVault).storeSystemKey(eq("AMSHA_TOOLS_GITHUB_TOKEN"), any());
    }
}
