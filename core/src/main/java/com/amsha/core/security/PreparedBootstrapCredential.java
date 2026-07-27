package com.amsha.core.security;

import java.util.Arrays;

/**
 * A closable, not-yet-persisted bootstrap credential bound to a typed reservation.
 *
 * <p>Preparation creates only mutable credential material; nothing is persisted or registered until
 * {@link BootstrapCredentialIssuer#activate(PreparedBootstrapCredential, int)} verifies the matching
 * {@code CREDENTIAL_ISSUED} reservation and durably writes the credential. Mutable bytes are zeroed on
 * {@link #close()}, so the holder must not retain {@link #rawKeyBytes()} after activation.
 */
public final class PreparedBootstrapCredential implements AutoCloseable {

    private final String ownerId;
    private final String rawKey;
    private final byte[] rawKeyBytes;
    private final String keyDigest;
    private boolean closed;

    public PreparedBootstrapCredential(String ownerId, String rawKey, byte[] rawKeyBytes, String keyDigest) {
        this.ownerId = ownerId;
        this.rawKey = rawKey;
        this.rawKeyBytes = rawKeyBytes;
        this.keyDigest = keyDigest;
    }

    public String ownerId() {
        return ownerId;
    }

    public String rawKey() {
        return rawKey;
    }

    public byte[] rawKeyBytes() {
        return rawKeyBytes;
    }

    public String keyDigest() {
        return keyDigest;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (rawKeyBytes != null) {
                Arrays.fill(rawKeyBytes, (byte) 0);
            }
        }
    }
}
