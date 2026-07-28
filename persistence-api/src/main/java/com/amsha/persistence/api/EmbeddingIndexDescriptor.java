package com.amsha.persistence.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable identity of every semantic choice that determines a persisted embedding index.
 * Provider identity is retained as provenance but excluded from the compatibility fingerprint.
 */
public record EmbeddingIndexDescriptor(
        int schemaVersion,
        String providerId,
        String spaceId,
        String modelRevision,
        String tokenizerRevision,
        String preprocessingRevision,
        String poolingStrategy,
        boolean l2Normalize,
        int vectorDimension,
        String numericPrecision,
        String chunkingStrategy,
        String chunkingRevision,
        int maxWindowTokens,
        int windowOverlapTokens,
        String multiWindowAggregation,
        String fingerprint) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public EmbeddingIndexDescriptor {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported embedding-index descriptor schema version");
        }
        providerId = requireNonBlank(providerId, "providerId");
        spaceId = requireNonBlank(spaceId, "spaceId");
        modelRevision = requireNonBlank(modelRevision, "modelRevision");
        tokenizerRevision = requireNonBlank(tokenizerRevision, "tokenizerRevision");
        preprocessingRevision = requireNonBlank(preprocessingRevision, "preprocessingRevision");
        poolingStrategy = requireNonBlank(poolingStrategy, "poolingStrategy");
        if (vectorDimension <= 0) throw new IllegalArgumentException("vectorDimension must be positive");
        numericPrecision = requireNonBlank(numericPrecision, "numericPrecision");
        chunkingStrategy = requireNonBlank(chunkingStrategy, "chunkingStrategy");
        chunkingRevision = requireNonBlank(chunkingRevision, "chunkingRevision");
        if (maxWindowTokens < 0) throw new IllegalArgumentException("maxWindowTokens must not be negative");
        if (windowOverlapTokens < 0) throw new IllegalArgumentException("windowOverlapTokens must not be negative");
        if (maxWindowTokens > 0 && windowOverlapTokens >= maxWindowTokens) {
            throw new IllegalArgumentException("windowOverlapTokens must be smaller than maxWindowTokens");
        }
        multiWindowAggregation = requireNonBlank(multiWindowAggregation, "multiWindowAggregation");
        fingerprint = requireSha256(fingerprint);
        String expected = calculateFingerprint(
                schemaVersion,
                spaceId,
                modelRevision,
                tokenizerRevision,
                preprocessingRevision,
                poolingStrategy,
                l2Normalize,
                vectorDimension,
                numericPrecision,
                chunkingStrategy,
                chunkingRevision,
                maxWindowTokens,
                windowOverlapTokens,
                multiWindowAggregation);
        if (!fingerprint.equals(expected)) {
            throw new IllegalArgumentException("fingerprint does not match embedding-index descriptor fields");
        }
    }

    public static EmbeddingIndexDescriptor create(
            String providerId,
            String spaceId,
            String modelRevision,
            String tokenizerRevision,
            String preprocessingRevision,
            String poolingStrategy,
            boolean l2Normalize,
            int vectorDimension,
            String numericPrecision,
            String chunkingStrategy,
            String chunkingRevision,
            int maxWindowTokens,
            int windowOverlapTokens,
            String multiWindowAggregation) {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        return new EmbeddingIndexDescriptor(
                schemaVersion,
                providerId,
                spaceId,
                modelRevision,
                tokenizerRevision,
                preprocessingRevision,
                poolingStrategy,
                l2Normalize,
                vectorDimension,
                numericPrecision,
                chunkingStrategy,
                chunkingRevision,
                maxWindowTokens,
                windowOverlapTokens,
                multiWindowAggregation,
                calculateFingerprint(
                        schemaVersion,
                        spaceId,
                        modelRevision,
                        tokenizerRevision,
                        preprocessingRevision,
                        poolingStrategy,
                        l2Normalize,
                        vectorDimension,
                        numericPrecision,
                        chunkingStrategy,
                        chunkingRevision,
                        maxWindowTokens,
                        windowOverlapTokens,
                        multiWindowAggregation));
    }

    public boolean isCompatibleWith(EmbeddingIndexDescriptor other) {
        return other != null && fingerprint.equals(other.fingerprint);
    }

    private static String calculateFingerprint(
            int schemaVersion,
            String spaceId,
            String modelRevision,
            String tokenizerRevision,
            String preprocessingRevision,
            String poolingStrategy,
            boolean l2Normalize,
            int vectorDimension,
            String numericPrecision,
            String chunkingStrategy,
            String chunkingRevision,
            int maxWindowTokens,
            int windowOverlapTokens,
            String multiWindowAggregation) {
        String canonical = String.join(
                "\n",
                Integer.toString(schemaVersion),
                spaceId,
                modelRevision,
                tokenizerRevision,
                preprocessingRevision,
                poolingStrategy,
                Boolean.toString(l2Normalize),
                Integer.toString(vectorDimension),
                numericPrecision,
                chunkingStrategy,
                chunkingRevision,
                Integer.toString(maxWindowTokens),
                Integer.toString(windowOverlapTokens),
                multiWindowAggregation);
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String requireSha256(String value) {
        String result = requireNonBlank(value, "fingerprint");
        if (!result.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("fingerprint must be SHA-256 hex");
        return result;
    }
}
