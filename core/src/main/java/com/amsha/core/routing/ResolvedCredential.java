package com.amsha.core.routing;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Short-lived, closable view of a decrypted connection credential.
 *
 * <p>Produced by the runtime credential resolver from a vault reference. The plaintext key bytes
 * are held only for the duration of a single client build and are zeroed on {@link #close()}. The
 * view must not be logged, retained in a pool, or serialized.</p>
 */
public final class ResolvedCredential implements AutoCloseable {

    private final String providerAdapterId;
    private final String baseUrl;
    private final byte[] keyBytes;

    public ResolvedCredential(String providerAdapterId, String baseUrl, byte[] keyBytes) {
        this.providerAdapterId = providerAdapterId;
        this.baseUrl = baseUrl;
        this.keyBytes = keyBytes != null ? keyBytes.clone() : new byte[0];
    }

    public String providerAdapterId() {
        return providerAdapterId;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return new String(keyBytes, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        Arrays.fill(keyBytes, (byte) 0);
    }
}
