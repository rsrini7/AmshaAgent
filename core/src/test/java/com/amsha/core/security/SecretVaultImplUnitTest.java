package com.amsha.core.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.SecretDto;
import com.amsha.persistence.api.SecretStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SecretVaultImplUnitTest {

    @Mock
    Argon2idKeyDerivation keyDerivation;

    @Mock
    VaultKeyCache keyCache;

    @Mock
    AesGcmSivCipher cipher;

    @Mock
    SecretStorePort secretStore;

    @Mock
    IdentityStorePort identityStore;

    @Mock
    AuditService auditService;

    @Mock
    AgentOtelContext otelContext;

    @InjectMocks
    SecretVaultImpl vault;

    @BeforeEach
    void setUp() {}

    @Test
    void testInitValidation() {
        assertThrows(IllegalArgumentException.class, () -> vault.init(null));
        assertThrows(IllegalArgumentException.class, () -> vault.init("   "));
        assertDoesNotThrow(() -> vault.init("valid-session"));
    }

    @Test
    void testStoreSystemKeySuccessful() {
        VaultKey masterKey = mock(VaultKey.class);
        when(keyDerivation.deriveSessionKey(VaultConstants.SYSTEM_SESSION_ID)).thenReturn(masterKey);
        AesGcmSivCipher.CipherResult cipherResult =
                new AesGcmSivCipher.CipherResult(new byte[] {1, 2}, new byte[] {3, 4});
        when(cipher.encrypt(any(), any(), eq("k1"))).thenReturn(cipherResult);

        assertDoesNotThrow(() -> vault.storeSystemKey("k1", new byte[] {10, 20}));
        verify(secretStore).save(any());
    }

    @Test
    void testSystemKeyExists() {
        when(secretStore.find(VaultConstants.SYSTEM_SESSION_ID, "k1"))
                .thenReturn(Optional.of(
                        new SecretDto(VaultConstants.SYSTEM_SESSION_ID, "k1", 1, new byte[0], new byte[0])));

        assertTrue(vault.systemKeyExists("k1"));
        assertFalse(vault.systemKeyExists("missing"));
    }
}
