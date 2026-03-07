package com.arcclaw.core.sandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultKey;

public class SandboxSecretResolverTest {

    @Mock
    SecretVault secretVault;

    @Mock
    VaultKey vaultKey;

    SandboxSecretResolver secretResolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        secretResolver = new SandboxSecretResolver(secretVault);
    }

    @Test
    void testResolveSecrets_nullMap_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> secretResolver.resolveSecrets(null));
    }

    @Test
    void testResolveSecrets_emptyMap_returnsEmpty() {
        Map<String, byte[]> result = secretResolver.resolveSecrets(new HashMap<>());
        assertTrue(result.isEmpty());
    }

    @Test
    void testResolveSecrets_missingKey_throwsSecurityException() {
        when(secretVault.get("missing")).thenReturn(Optional.empty());
        Map<String, String> required = Map.of("ENV_VAR", "missing");
        
        assertThrows(SecurityException.class, () -> secretResolver.resolveSecrets(required));
    }

    @Test
    void testResolveSecrets_success_returnsByteArrayMap() {
        byte[] secretData = "secret-value".getBytes();
        when(vaultKey.get()).thenReturn(secretData);
        when(secretVault.get("key1")).thenReturn(Optional.of(vaultKey));
        
        Map<String, String> required = Map.of("VAR1", "key1");
        Map<String, byte[]> result = secretResolver.resolveSecrets(required);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        assertArrayEquals(secretData, result.get("VAR1"));
        verify(vaultKey).close();
    }
}
