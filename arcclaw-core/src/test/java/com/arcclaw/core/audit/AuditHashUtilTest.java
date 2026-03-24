package com.arcclaw.core.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultKey;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class AuditHashUtilTest {

    @Test
    void testConstructor_Success() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        byte[] keyBytes = new byte[32];
        VaultKey vaultKey = new VaultKey(keyBytes);

        when(vault.retrieveSystemKey("arcclaw-audit-hmac-key")).thenReturn(Optional.of(vaultKey));

        @SuppressWarnings("unchecked")
        Instance<SecretVault> vaultInstance = Mockito.mock(Instance.class);
        when(vaultInstance.get()).thenReturn(vault);

        AuditHashUtil util = new AuditHashUtil(vaultInstance);
        assertNotNull(util);
    }

    @Test
    void missingHmacKey_ThrowsIllegalStateException_NotPlaceholderString() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        when(vault.retrieveSystemKey("arcclaw-audit-hmac-key")).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        Instance<SecretVault> vaultInstance = Mockito.mock(Instance.class);
        when(vaultInstance.get()).thenReturn(vault);

        AuditHashUtil util = new AuditHashUtil(vaultInstance);
        assertThrows(IllegalStateException.class, () -> util.hmacHex("test"),
            "hmacHex() must throw when HMAC key is unavailable — returning a placeholder silently breaks the WORM audit chain");
    }

    @Test
    void testHmac_Deterministic() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        byte[] keyBytes = "fixed-test-key-32-bytes-long-123".getBytes(StandardCharsets.UTF_8);
        VaultKey vaultKey = new VaultKey(keyBytes);

        when(vault.retrieveSystemKey("arcclaw-audit-hmac-key")).thenReturn(Optional.of(vaultKey));

        @SuppressWarnings("unchecked")
        Instance<SecretVault> vaultInstance = Mockito.mock(Instance.class);
        when(vaultInstance.get()).thenReturn(vault);

        AuditHashUtil util = new AuditHashUtil(vaultInstance);

        String input = "test-data";
        String hmac1 = util.hmacHex(input);
        String hmac2 = util.hmacHex(input);

        assertEquals(hmac1, hmac2, "HMAC should be deterministic for same input");
    }

    @Test
    void testHmac_ChangesWithInput() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        byte[] keyBytes = "fixed-test-key-32-bytes-long-123".getBytes(StandardCharsets.UTF_8);
        VaultKey vaultKey = new VaultKey(keyBytes);

        when(vault.retrieveSystemKey("arcclaw-audit-hmac-key")).thenReturn(Optional.of(vaultKey));

        @SuppressWarnings("unchecked")
        Instance<SecretVault> vaultInstance = Mockito.mock(Instance.class);
        when(vaultInstance.get()).thenReturn(vault);

        AuditHashUtil util = new AuditHashUtil(vaultInstance);

        String hmac1 = util.hmacHex("input-1");
        String hmac2 = util.hmacHex("input-2");

        assertNotEquals(hmac1, hmac2, "HMAC should change when input changes");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSha256Hex() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        when(vault.retrieveSystemKey(anyString())).thenReturn(Optional.of(new VaultKey(new byte[32])));

        Instance<SecretVault> vaultInstance = Mockito.mock(Instance.class);
        when(vaultInstance.get()).thenReturn(vault);

        AuditHashUtil util = new AuditHashUtil(vaultInstance);

        String input = "hello";
        // SHA-256 of "hello" (UTF-8)
        String expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        assertEquals(expected, util.sha256hex(input));
        assertEquals("genesis-fallback-null-input", util.sha256hex(null));
    }
}
