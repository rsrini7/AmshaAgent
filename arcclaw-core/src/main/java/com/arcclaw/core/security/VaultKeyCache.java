package com.arcclaw.core.security;

import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PreDestroy;

import com.arcclaw.core.scope.WorkspaceScoped;

/**
 * Caches the derived session key for the duration of a Workspace session.
 * Argon2id is intentionally expensive (Argon2idKeyDerivation), so we
 * derive once and share it within the scope.
 */
@WorkspaceScoped
public class VaultKeyCache {

    // volatile: ensures cross-thread visibility of the reference; lock guards the derivation block
    private volatile VaultKey cachedKey;

    // ReentrantLock is instantiated on the BEAN INSTANCE, not on the proxy.
    // synchronized(this) would lock the proxy - this does not.
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Returns the cached key for this session, deriving it on first access.
     * Fast path - volatile read, no lock acquisition.
     */
    public VaultKey getOrDerive(String sessionId, Argon2idKeyDerivation derivation) {
        VaultKey current = cachedKey;
        if (current != null && !current.isDestroyed()) {
            return current;
        }

        lock.lock();
        try {
            // Re-check inside lock - another thread may have derived while we waited
            current = cachedKey;
            if (current == null || current.isDestroyed()) {
                cachedKey = derivation.deriveSessionKey(sessionId);
                current = cachedKey;
            }
            return current;
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    void wipe() {
        lock.lock();
        try {
            if (cachedKey != null) {
                cachedKey.close(); // VaultKey implements AutoCloseable - zeroes key bytes
                cachedKey = null;
            }
        } finally {
            lock.unlock();
        }
    }
}

