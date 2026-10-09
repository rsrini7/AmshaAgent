package com.amsha.core.classifier.provider;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Copies an environment-backed provider API key into request-owned credential bytes.
 *
 * <p>Decision provider keys use the same direct environment configuration convention as Amsha's
 * other providers. The source {@link String} is held by the configuration system for the process
 * lifetime; this class does not retain it. It creates a per-request UTF-8 byte copy that the caller
 * must clear after engine construction and use.
 *
 * <p><b>The returned array is owned by the caller and must be zeroed.</b>
 */
public final class DecisionCredentialResolver {
    private DecisionCredentialResolver() {}

    /**
     * Copies configured credential material.
     *
     * @param apiKey the direct provider API key; blank means no credential is configured
     * @return request-owned credential bytes, or empty when no key is configured
     */
    public static Optional<byte[]> resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(apiKey.getBytes(StandardCharsets.UTF_8));
    }

    /** Zeroes credential material. Call in a {@code finally} once the engine has consumed it. */
    public static void zero(byte[] credential) {
        if (credential != null) {
            Arrays.fill(credential, (byte) 0);
        }
    }
}
