package com.amsha.persistence.api;

import java.util.Objects;
import java.util.Optional;

/** Result of comparing one persisted vector namespace with the active embedding descriptor. */
public record VectorIndexCompatibility(
        VectorIndexNamespace namespace, Status status, String activeFingerprint, Optional<String> storedFingerprint) {

    private static final String UNAVAILABLE_FINGERPRINT = "provider-unavailable";

    public enum Status {
        INITIALIZED,
        MATCH,
        MISSING_METADATA,
        MISMATCH,
        UNAVAILABLE
    }

    public VectorIndexCompatibility {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(activeFingerprint, "activeFingerprint");
        Objects.requireNonNull(storedFingerprint, "storedFingerprint");
    }

    public boolean vectorEnabled() {
        return status == Status.INITIALIZED || status == Status.MATCH;
    }

    public static VectorIndexCompatibility unavailable(VectorIndexNamespace namespace) {
        return new VectorIndexCompatibility(namespace, Status.UNAVAILABLE, UNAVAILABLE_FINGERPRINT, Optional.empty());
    }

    public static VectorIndexCompatibility assess(
            VectorIndexNamespace namespace,
            EmbeddingIndexDescriptor active,
            Optional<EmbeddingIndexDescriptor> stored,
            boolean indexEmpty) {
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(stored, "stored");
        if (stored.filter(active::isCompatibleWith).isPresent()) {
            return new VectorIndexCompatibility(
                    namespace, Status.MATCH, active.fingerprint(), stored.map(EmbeddingIndexDescriptor::fingerprint));
        }
        if (indexEmpty) {
            return new VectorIndexCompatibility(
                    namespace,
                    Status.INITIALIZED,
                    active.fingerprint(),
                    stored.map(EmbeddingIndexDescriptor::fingerprint));
        }
        Status incompatibleStatus = stored.isPresent() ? Status.MISMATCH : Status.MISSING_METADATA;
        return new VectorIndexCompatibility(
                namespace, incompatibleStatus, active.fingerprint(), stored.map(EmbeddingIndexDescriptor::fingerprint));
    }
}
