package com.amsha.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.SecretDto;
import com.amsha.persistence.api.SecretStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

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

    @Test
    void testUserScopedStoreGetRemove() {
        VaultKey sessionKey = mock(VaultKey.class);
        when(keyCache.getOrDerive(anyString(), eq(keyDerivation))).thenReturn(sessionKey);
        AesGcmSivCipher.CipherResult cipherResult =
                new AesGcmSivCipher.CipherResult(new byte[] {1, 2}, new byte[] {3, 4});
        when(cipher.encrypt(any(), eq(sessionKey), eq("u-key"))).thenReturn(cipherResult);

        vault.store("u-key", new byte[] {100}, "usr-1");
        verify(secretStore).save(any());

        SecretDto dto = new SecretDto("USER:usr-1", "u-key", 1, new byte[] {1, 2}, new byte[] {3, 4});
        when(secretStore.find("USER:usr-1", "u-key")).thenReturn(Optional.of(dto));
        when(cipher.decrypt(any(), any(), eq(sessionKey), eq("u-key"))).thenReturn(new byte[] {100});

        Optional<VaultKey> retrieved = vault.get("u-key", "usr-1");
        assertTrue(retrieved.isPresent());

        vault.remove("u-key", "usr-1");
        verify(secretStore).delete("USER:usr-1", "u-key");
    }

    @Test
    void testWorkspaceOperationsSecurityGuard() {
        Instant now = Instant.now();
        WorkspaceMembershipDto activeOwner =
                new WorkspaceMembershipDto("ws-1", "usr-1", "OWNER", "ACTIVE", 1, now, now);
        when(identityStore.findMembership("ws-1", "usr-1")).thenReturn(Optional.of(activeOwner));

        VaultKey sessionKey = mock(VaultKey.class);
        when(keyCache.getOrDerive(anyString(), eq(keyDerivation))).thenReturn(sessionKey);
        when(cipher.encrypt(any(), any(), any()))
                .thenReturn(new AesGcmSivCipher.CipherResult(new byte[0], new byte[0]));

        assertDoesNotThrow(() -> vault.storeWorkspace("ws-secret", new byte[] {1}, "ws-1", "usr-1"));

        WorkspaceMembershipDto nonAdmin = new WorkspaceMembershipDto("ws-1", "usr-2", "MEMBER", "ACTIVE", 1, now, now);
        when(identityStore.findMembership("ws-1", "usr-2")).thenReturn(Optional.of(nonAdmin));

        assertThrows(SecurityException.class, () -> vault.storeWorkspace("ws-secret", new byte[] {1}, "ws-1", "usr-2"));
        assertThrows(SecurityException.class, () -> vault.removeWorkspace("ws-secret", "ws-1", "usr-2"));

        Optional<VaultKey> wsKey = vault.getWorkspace("ws-secret", "ws-1", "usr-2");
        assertNotNull(wsKey);
    }
}
