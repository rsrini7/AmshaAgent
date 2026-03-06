package com.arcclaw.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VaultKeyCacheTest {

    private VaultKeyCache cache;
    private Argon2idKeyDerivation derivation;
    private String sessionId = "test-session";

    @BeforeEach
    void setup() {
        cache = new VaultKeyCache();
        derivation = mock(Argon2idKeyDerivation.class);
    }

    @Test
    void testDeriveCalledOnceForMultipleAccesses() {
        byte[] keyBytes = new byte[32];
        VaultKey key = new VaultKey(keyBytes);
        when(derivation.deriveSessionKey(sessionId)).thenReturn(key);

        // Call 3 times
        VaultKey key1 = cache.getOrDerive(sessionId, derivation);
        VaultKey key2 = cache.getOrDerive(sessionId, derivation);
        VaultKey key3 = cache.getOrDerive(sessionId, derivation);

        assertSame(key1, key2);
        assertSame(key1, key3);
        verify(derivation, times(1)).deriveSessionKey(sessionId);
    }

    @Test
    void testWipeZeroesKey() {
        byte[] keyBytes = new byte[32];
        VaultKey key = new VaultKey(keyBytes);
        when(derivation.deriveSessionKey(sessionId)).thenReturn(key);

        VaultKey cached = cache.getOrDerive(sessionId, derivation);
        assertFalse(cached.isDestroyed());

        cache.wipe();

        assertTrue(cached.isDestroyed());
        // Verify safety check after wipe (should return null in getOrDerive if we bypass lock, 
        // but here we just test that it's wiped)
    }

    @Test
    void testGetOrDeriveAfterWipeRederives() {
        VaultKey key1 = new VaultKey(new byte[32]);
        VaultKey key2 = new VaultKey(new byte[32]);
        when(derivation.deriveSessionKey(sessionId))
                .thenReturn(key1)
                .thenReturn(key2);

        VaultKey first = cache.getOrDerive(sessionId, derivation);
        assertSame(key1, first);

        cache.wipe();
        assertTrue(key1.isDestroyed());

        VaultKey second = cache.getOrDerive(sessionId, derivation);
        assertSame(key2, second);
        assertFalse(key2.isDestroyed());

        verify(derivation, times(2)).deriveSessionKey(sessionId);
    }

    @Test
    void testSafetyFallbackIfKeyDestroyedExternally() {
        VaultKey key1 = new VaultKey(new byte[32]);
        VaultKey key2 = new VaultKey(new byte[32]);
        when(derivation.deriveSessionKey(sessionId))
                .thenReturn(key1)
                .thenReturn(key2);

        VaultKey first = cache.getOrDerive(sessionId, derivation);
        assertSame(key1, first);

        // Manually destroy the key to simulate a scope cleanup that didn't go through wipe()
        key1.close();

        // Should detect it's destroyed and re-derive
        VaultKey second = cache.getOrDerive(sessionId, derivation);
        assertSame(key2, second);
        verify(derivation, times(2)).deriveSessionKey(sessionId);
    }
}
