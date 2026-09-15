package com.amsha.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.context.ContextNotActiveException;

import java.time.Instant;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.observability.AgentOTelConstants;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.SecretDto;
import com.amsha.persistence.api.SecretStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;
import com.amsha.tool.api.AgentSentinels;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

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

    // ------------------------------------------------------------------
    // System master-key lifecycle: derivation cache + @PreDestroy cleanup
    // ------------------------------------------------------------------

    @Test
    void cleanup_closesCachedSystemMasterKey_andSecondCallIsNoOp() {
        VaultKey masterKey = new VaultKey(new byte[] {1, 2, 3});
        when(keyDerivation.deriveSessionKey(VaultConstants.SYSTEM_SESSION_ID)).thenReturn(masterKey);
        when(cipher.encrypt(any(), any(), any()))
                .thenReturn(new AesGcmSivCipher.CipherResult(new byte[0], new byte[0]));

        // First store derives + caches; the second hits the cache (line 106) and must NOT re-derive.
        vault.storeSystemKey("k1", new byte[] {9});
        vault.storeSystemKey("k2", new byte[] {9});
        verify(keyDerivation, times(1)).deriveSessionKey(VaultConstants.SYSTEM_SESSION_ID);

        // cleanup() (92-101) closes the cached key; the second call exercises the null branch of line 94.
        vault.cleanup();
        assertTrue(masterKey.isDestroyed());
        vault.cleanup();
    }

    // ------------------------------------------------------------------
    // WorkspaceContext-aware convenience overloads (no explicit userId)
    // ------------------------------------------------------------------

    @Test
    void storeAndGetWithoutUser_fallBackToSystemSessionWhenUnbound() {
        VaultKey sessionKey = new VaultKey(new byte[] {1, 2, 3});
        when(keyCache.getOrDerive(eq(VaultConstants.SYSTEM_SESSION_ID), eq(keyDerivation)))
                .thenReturn(sessionKey);
        when(cipher.encrypt(any(), eq(sessionKey), eq("sys-key")))
                .thenReturn(new AesGcmSivCipher.CipherResult(new byte[] {1}, new byte[] {2}));
        when(secretStore.find(VaultConstants.SYSTEM_SESSION_ID, "sys-key")).thenReturn(Optional.empty());

        vault.store("sys-key", new byte[] {5});
        verify(secretStore).save(argThat(dto -> VaultConstants.SYSTEM_SESSION_ID.equals(dto.sessionId())));

        Optional<VaultKey> result = vault.get("sys-key");
        assertTrue(result.isEmpty());
    }

    @Test
    void storeAndGetWithoutUser_useBoundSessionId() throws Exception {
        VaultKey sessionKey = new VaultKey(new byte[] {1, 2, 3});
        when(keyCache.getOrDerive(eq("sess-1"), eq(keyDerivation))).thenReturn(sessionKey);
        when(cipher.encrypt(any(), eq(sessionKey), eq("s")))
                .thenReturn(new AesGcmSivCipher.CipherResult(new byte[] {1}, new byte[] {2}));
        when(secretStore.find("sess-1", "s")).thenReturn(Optional.empty());

        WorkspaceContext.run("sess-1", "u1", "c1", AutonomyLevel.FULL, "coordinator", () -> {
            vault.store("s", new byte[] {5});
            Optional<VaultKey> result = vault.get("s");
            assertTrue(result.isEmpty());
            return null;
        });

        verify(secretStore).save(argThat(dto -> "sess-1".equals(dto.sessionId())));
    }

    // ------------------------------------------------------------------
    // Session-key acquisition fallback + KeyHolder close semantics
    // ------------------------------------------------------------------

    @Test
    void store_fallsBackToDirectDerivationWhenContextNotActive_closingTheKey() {
        when(keyCache.getOrDerive(anyString(), eq(keyDerivation)))
                .thenThrow(new ContextNotActiveException("no request context"));
        VaultKey derived = new VaultKey(new byte[] {1, 2, 3});
        when(keyDerivation.deriveSessionKey("USER:u1")).thenReturn(derived);
        when(cipher.encrypt(any(), eq(derived), eq("k")))
                .thenReturn(new AesGcmSivCipher.CipherResult(new byte[] {1}, new byte[] {2}));

        vault.store("k", new byte[] {5}, "u1");

        // The directly-derived key is owned by the KeyHolder (closeOnExit == true) and wiped on close (line 161).
        assertTrue(derived.isDestroyed());
    }

    // ------------------------------------------------------------------
    // OTel span instrumentation on the store path
    // ------------------------------------------------------------------

    @Test
    void store_spanRecordsErrorAndRethrowsWhenPersistFails() {
        Span span = mock(Span.class);
        when(otelContext.childSpan(anyString(), anyString())).thenReturn(span);
        VaultKey sessionKey = new VaultKey(new byte[] {1, 2, 3});
        when(keyCache.getOrDerive(anyString(), eq(keyDerivation))).thenReturn(sessionKey);
        when(cipher.encrypt(any(), any(), any()))
                .thenReturn(new AesGcmSivCipher.CipherResult(new byte[] {1}, new byte[] {2}));
        doThrow(new RuntimeException("persist boom")).when(secretStore).save(any());

        assertThrows(RuntimeException.class, () -> vault.store("k", new byte[] {5}, "u1"));

        verify(span).setStatus(StatusCode.ERROR, "persist boom");
        verify(span).recordException(any(RuntimeException.class));
        verify(span).end();
    }

    // ------------------------------------------------------------------
    // OTel span instrumentation on the get path
    // ------------------------------------------------------------------

    @Test
    void get_spanMarksNotFoundWhenSecretMissing() {
        Span span = mock(Span.class);
        when(otelContext.childSpan(anyString(), anyString())).thenReturn(span);
        when(secretStore.find("USER:u1", "k")).thenReturn(Optional.empty());

        Optional<VaultKey> result = vault.get("k", "u1");

        assertTrue(result.isEmpty());
        verify(span).setAttribute(AgentOTelConstants.ATTR_VAULT_FOUND, false);
        verify(span).setStatus(StatusCode.OK);
    }

    @Test
    void get_spanMarksFoundWhenSecretPresent() {
        Span span = mock(Span.class);
        when(otelContext.childSpan(anyString(), anyString())).thenReturn(span);
        VaultKey sessionKey = new VaultKey(new byte[] {1, 2, 3});
        when(keyCache.getOrDerive(eq("USER:u1"), eq(keyDerivation))).thenReturn(sessionKey);
        when(secretStore.find("USER:u1", "k"))
                .thenReturn(Optional.of(new SecretDto("USER:u1", "k", 1, new byte[] {1}, new byte[] {2})));
        when(cipher.decrypt(any(), any(), eq(sessionKey), eq("k"))).thenReturn(new byte[] {7});

        Optional<VaultKey> result = vault.get("k", "u1");

        assertTrue(result.isPresent());
        verify(span).setAttribute("vault.found", true);
        verify(span).setStatus(StatusCode.OK);
    }

    @Test
    void get_spanRecordsErrorAndRethrowsOnDecryptFailure() {
        Span span = mock(Span.class);
        when(otelContext.childSpan(anyString(), anyString())).thenReturn(span);
        VaultKey sessionKey = new VaultKey(new byte[] {1, 2, 3});
        when(keyCache.getOrDerive(eq("USER:u1"), eq(keyDerivation))).thenReturn(sessionKey);
        when(secretStore.find("USER:u1", "k"))
                .thenReturn(Optional.of(new SecretDto("USER:u1", "k", 1, new byte[] {1}, new byte[] {2})));
        when(cipher.decrypt(any(), any(), eq(sessionKey), eq("k"))).thenThrow(new SecurityException("bad tag"));

        assertThrows(SecurityException.class, () -> vault.get("k", "u1"));

        verify(span).setStatus(StatusCode.ERROR, "bad tag");
        verify(span).recordException(any(SecurityException.class));
        verify(span).end();
    }

    // ------------------------------------------------------------------
    // Workspace owner/admin authorization (delete path + blank-id guards)
    // ------------------------------------------------------------------

    @Test
    void removeWorkspace_adminDeletesSharedSecret() {
        Instant now = Instant.now();
        when(identityStore.findMembership("ws-1", "usr-1"))
                .thenReturn(Optional.of(new WorkspaceMembershipDto("ws-1", "usr-1", "ADMIN", "ACTIVE", 1, now, now)));

        vault.removeWorkspace("ws-secret", "ws-1", "usr-1");

        verify(secretStore).delete("WORKSPACE:ws-1", "ws-secret");
    }

    @Test
    void workspaceOperations_blankIdsRejected() {
        assertThrows(IllegalArgumentException.class, () -> vault.storeWorkspace("k", new byte[] {1}, "  ", "u1"));
        assertThrows(IllegalArgumentException.class, () -> vault.storeWorkspace("k", new byte[] {1}, "ws-1", null));
        assertThrows(IllegalArgumentException.class, () -> vault.getWorkspace("k", "  ", "u1"));
    }

    @Test
    void userScopedOperations_blankUserIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> vault.store("k", new byte[] {1}, ""));
        assertThrows(IllegalArgumentException.class, () -> vault.get("k", "  "));
    }

    @Test
    void getWorkspace_nonMemberBlocked() {
        when(identityStore.findMembership("ws-1", "outsider")).thenReturn(Optional.empty());

        assertThrows(SecurityException.class, () -> vault.getWorkspace("k", "ws-1", "outsider"));
    }

    // ------------------------------------------------------------------
    // System-key direct store/retrieve/remove paths
    // ------------------------------------------------------------------

    @Test
    void retrieveSystemKey_missingReturnsEmpty_andPresentDecrypts() {
        when(secretStore.find(VaultConstants.SYSTEM_SESSION_ID, "missing")).thenReturn(Optional.empty());
        assertTrue(vault.retrieveSystemKey("missing").isEmpty());

        VaultKey masterKey = new VaultKey(new byte[] {1, 2, 3});
        when(keyDerivation.deriveSessionKey(VaultConstants.SYSTEM_SESSION_ID)).thenReturn(masterKey);
        when(secretStore.find(VaultConstants.SYSTEM_SESSION_ID, "sys"))
                .thenReturn(Optional.of(
                        new SecretDto(VaultConstants.SYSTEM_SESSION_ID, "sys", 1, new byte[] {1}, new byte[] {2})));
        when(cipher.decrypt(any(), any(), eq(masterKey), eq("sys"))).thenReturn(new byte[] {9});

        assertTrue(vault.retrieveSystemKey("sys").isPresent());
    }

    @Test
    void retrieveSystemKey_rethrowsSecurityExceptionOnDecryptFailure() {
        VaultKey masterKey = new VaultKey(new byte[] {1, 2, 3});
        when(keyDerivation.deriveSessionKey(VaultConstants.SYSTEM_SESSION_ID)).thenReturn(masterKey);
        when(secretStore.find(VaultConstants.SYSTEM_SESSION_ID, "sys"))
                .thenReturn(Optional.of(
                        new SecretDto(VaultConstants.SYSTEM_SESSION_ID, "sys", 1, new byte[] {1}, new byte[] {2})));
        when(cipher.decrypt(any(), any(), eq(masterKey), eq("sys"))).thenThrow(new SecurityException("bad"));

        assertThrows(SecurityException.class, () -> vault.retrieveSystemKey("sys"));
    }

    @Test
    void storeSystemKey_rethrowsOnEncryptFailure() {
        VaultKey masterKey = new VaultKey(new byte[] {1, 2, 3});
        when(keyDerivation.deriveSessionKey(VaultConstants.SYSTEM_SESSION_ID)).thenReturn(masterKey);
        when(cipher.encrypt(any(), any(), any())).thenThrow(new RuntimeException("enc boom"));

        assertThrows(RuntimeException.class, () -> vault.storeSystemKey("k1", new byte[] {9}));
    }

    @Test
    void removeSystemKey_deletesAndAudits() {
        vault.removeSystemKey("k1");

        verify(secretStore).delete(VaultConstants.SYSTEM_SESSION_ID, "k1");
        verify(auditService)
                .record(
                        eq(AgentSentinels.SYSTEM_SESSION),
                        eq(AgentSentinels.SYSTEM_USER),
                        eq(AgentSentinels.SYSTEM_ROLE),
                        eq(SecurityEventType.VAULT_ACCESS),
                        contains("DELETE_SYSTEM"));
    }
}
