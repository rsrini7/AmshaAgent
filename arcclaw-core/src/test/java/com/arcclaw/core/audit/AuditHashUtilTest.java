package com.arcclaw.core.audit;

import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultKey;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class AuditHashUtilTest {

    @Test
    void testConstructor_Success() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        byte[] keyBytes = new byte[32];
        VaultKey vaultKey = new VaultKey(keyBytes);
        
        when(vault.retrieveSystemKey("arcclaw-audit-hmac-key")).thenReturn(Optional.of(vaultKey));
        
        AuditHashUtil util = new AuditHashUtil(vault);
        assertNotNull(util);
    }

    @Test
    void testConstructor_MissingKey_ThrowsException() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        when(vault.retrieveSystemKey(anyString())).thenReturn(Optional.empty());
        
        assertThrows(IllegalStateException.class, () -> new AuditHashUtil(vault));
    }

    @Test
    void testHmac_Deterministic() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        byte[] keyBytes = "fixed-test-key-32-bytes-long-123".getBytes(StandardCharsets.UTF_8);
        VaultKey vaultKey = new VaultKey(keyBytes);
        
        when(vault.retrieveSystemKey("arcclaw-audit-hmac-key")).thenReturn(Optional.of(vaultKey));
        
        AuditHashUtil util = new AuditHashUtil(vault);
        
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
        
        AuditHashUtil util = new AuditHashUtil(vault);
        
        String hmac1 = util.hmacHex("input-1");
        String hmac2 = util.hmacHex("input-2");
        
        assertNotEquals(hmac1, hmac2, "HMAC should change when input changes");
    }

    @Test
    void testSha256Hex() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        when(vault.retrieveSystemKey(anyString())).thenReturn(Optional.of(new VaultKey(new byte[32])));
        AuditHashUtil util = new AuditHashUtil(vault);

        String input = "hello";
        // SHA-256 of "hello" (UTF-8)
        String expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        assertEquals(expected, util.sha256hex(input));
        assertEquals("null", util.sha256hex(null));
    }
}
