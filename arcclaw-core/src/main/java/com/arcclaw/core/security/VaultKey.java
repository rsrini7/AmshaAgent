package com.arcclaw.core.security;

import java.util.Arrays;

/**
 * A secure wrapper for sensitive key material (secrets, salts, derived keys).
 * <p>
 * This class implements {@link AutoCloseable} to support the try-with-resources
 * pattern.
 * When closed, the underlying byte array is overwritten with zeros to prevent
 * sensitive data from lingering in memory until garbage collection.
 * <p>
 * <strong>Zero-String Rule:</strong> Secrets are never exposed as Strings.
 * {@link #toString()} is overridden to return a safe placeholder.
 */
public final class VaultKey implements AutoCloseable {

    private final byte[] key;
    private boolean destroyed = false;

    /**
     * Creates a new VaultKey by WRAPPING the provided byte array.
     * The array is NOT copied; the VaultKey takes ownership.
     *
     * @param key The sensitive key bytes. Must not be null.
     */
    public VaultKey(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key material cannot be null");
        }
        this.key = key;
    }

    /**
     * Returns a direct reference to the key material.
     * Callers must NOT store this reference beyond the immediate scope of use.
     *
     * @return The key bytes.
     * @throws IllegalStateException if the key has been destroyed.
     */
    public byte[] get() {
        checkDestroyed();
        return key;
    }

    /**
     * Wipes the underlying key material by filling the array with zeros.
     * Idempotent.
     */
    @Override
    public void close() {
        if (!destroyed) {
            Arrays.fill(key, (byte) 0);
            destroyed = true;
        }
    }

    /**
     * @return True if the key has been wiped.
     */
    public boolean isDestroyed() {
        return destroyed;
    }

    private void checkDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("VaultKey has been destroyed/closed");
        }
    }

    /**
     * Safe toString() that does NOT leak the key.
     */
    @Override
    public String toString() {
        return "VaultKey[destroyed=" + destroyed + ", length=" + (destroyed ? 0 : key.length) + "]";
    }
}
