package com.arcclaw.core.sandbox;

import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SandboxSecretResolverTest {

    @Mock
    SecretVault secretVault;

    @Mock
    VaultKey vaultKey;

    @InjectMocks
    SandboxSecretResolver secretResolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
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
