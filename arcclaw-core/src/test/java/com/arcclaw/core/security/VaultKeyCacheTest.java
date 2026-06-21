package com.arcclaw.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    void testIdempotency() {
        byte[] keyBytes = new byte[32];
        VaultKey key = new VaultKey(keyBytes);
        when(derivation.deriveSessionKey(sessionId)).thenReturn(key);

        VaultKey first = null;
        for (int i = 0; i < 10; i++) {
            VaultKey current = cache.getOrDerive(sessionId, derivation);
            if (first == null) {
                first = current;
            } else {
                assertSame(first, current, "Reference should be identical");
            }
        }
        verify(derivation, times(1)).deriveSessionKey(sessionId);
    }

    @Test
    void testConcurrencyWithVirtualThreads() throws InterruptedException {
        // We use an AtomicInteger to count actual invocations in the mock
        AtomicInteger derivationCount = new AtomicInteger(0);

        when(derivation.deriveSessionKey(anyString())).thenAnswer(invocation -> {
            derivationCount.incrementAndGet();
            // Simulate some work to increase race condition probability
            Thread.sleep(50);
            return new VaultKey(new byte[32]);
        });

        int threadCount = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<VaultKey> ref1 = new AtomicReference<>();
        AtomicReference<VaultKey> ref2 = new AtomicReference<>();

        Thread.ofVirtual().start(() -> {
            try {
                startLatch.await();
                ref1.set(cache.getOrDerive(sessionId, derivation));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        Thread.ofVirtual().start(() -> {
            try {
                startLatch.await();
                ref2.set(cache.getOrDerive(sessionId, derivation));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        assertTrue(finished, "Threads should finish in time");

        assertEquals(1, derivationCount.get(), "deriveSessionKey should be called exactly once");
        assertSame(ref1.get(), ref2.get(), "Both threads should get the same key instance");
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
    }

    @Test
    void testGetOrDeriveAfterWipeRederives() {
        VaultKey key1 = new VaultKey(new byte[32]);
        VaultKey key2 = new VaultKey(new byte[32]);
        when(derivation.deriveSessionKey(sessionId)).thenReturn(key1).thenReturn(key2);

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
        when(derivation.deriveSessionKey(sessionId)).thenReturn(key1).thenReturn(key2);

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
