package com.arcclaw.core.security;

import com.arcclaw.core.scope.WorkspaceScoped;
import jakarta.annotation.PreDestroy;

/**
 * Caches the derived session key for the duration of a Workspace session.
 * Argon2id is intentionally expensive (Argon2idKeyDerivation), so we
 * derive once and share it within the scope.
 */
@WorkspaceScoped
public class VaultKeyCache {

    private volatile VaultKey cachedKey;

    /**
     * Returns the cached key for this session, deriving it on first access.
     * Thread-safe via double-checked locking — WorkspaceScoped beans are
     * per-session, so contention only exists within one session's threads.
     */
    public VaultKey getOrDerive(String sessionId, Argon2idKeyDerivation derivation) {
        // Safety: if wipe() was somehow skipped and the key was destroyed by another path,
        // treat stale key as absent.
        if (cachedKey != null && cachedKey.isDestroyed()) {
            cachedKey = null;
        }

        if (cachedKey != null) return cachedKey;

        synchronized (this) {
            // Re-check after acquiring lock
            if (cachedKey != null && cachedKey.isDestroyed()) {
                cachedKey = null;
            }

            if (cachedKey == null) {
                cachedKey = derivation.deriveSessionKey(sessionId);
            }
        }
        return cachedKey;
    }

    @PreDestroy
    void wipe() {
        if (cachedKey != null) {
            cachedKey.close(); // VaultKey implements AutoCloseable — zeroes key bytes
            cachedKey = null;
        }
    }
}
