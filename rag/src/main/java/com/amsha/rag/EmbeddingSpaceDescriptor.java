package com.amsha.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable identity of the vector space emitted by an embedding provider.
 *
 * <p>Providers may share a space only when their configured identity and revision
 * have been independently verified. Provider identity is retained for diagnostics but
 * does not determine compatibility.
 */
public record EmbeddingSpaceDescriptor(String providerId, String spaceId, String revision, String fingerprint) {

    public EmbeddingSpaceDescriptor {
        providerId = requireNonBlank(providerId, "providerId");
        spaceId = requireNonBlank(spaceId, "spaceId");
        revision = requireNonBlank(revision, "revision");
        fingerprint = requireNonBlank(fingerprint, "fingerprint");
        if (!fingerprint.equals(fingerprint(spaceId, revision))) {
            throw new IllegalArgumentException("fingerprint does not match spaceId and revision");
        }
    }

    public static EmbeddingSpaceDescriptor providerBound(String providerId, String modelName) {
        String id = requireNonBlank(providerId, "providerId") + ":" + requireNonBlank(modelName, "modelName");
        return new EmbeddingSpaceDescriptor(providerId, id, "unverified", fingerprint(id, "unverified"));
    }

    public static EmbeddingSpaceDescriptor configured(String providerId, String spaceId, String revision) {
        return new EmbeddingSpaceDescriptor(providerId, spaceId, revision, fingerprint(spaceId, revision));
    }

    public boolean isCompatibleWith(EmbeddingSpaceDescriptor other) {
        return other != null && fingerprint.equals(other.fingerprint);
    }

    private static String fingerprint(String spaceId, String revision) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest((spaceId + "\n" + revision).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
