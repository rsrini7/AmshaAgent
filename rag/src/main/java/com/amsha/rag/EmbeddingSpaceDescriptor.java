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
public record EmbeddingSpaceDescriptor(
        String providerId,
        String spaceId,
        String modelRevision,
        String tokenizerRevision,
        String preprocessingRevision,
        String poolingStrategy,
        boolean l2Normalize,
        int vectorDimension,
        String numericPrecision,
        int maxSequenceLength,
        String chunkingStrategy,
        String chunkingRevision,
        int maxWindowTokens,
        int windowOverlapTokens,
        String multiWindowAggregation,
        String fingerprint) {

    private static final String DEFAULT_CHUNKING_STRATEGY = "FIRST_MAX_TOKENS";
    private static final String DEFAULT_CHUNKING_REVISION = "v1";
    private static final String DEFAULT_MULTI_WINDOW_AGGREGATION = "DIRECT";

    public EmbeddingSpaceDescriptor {
        providerId = requireNonBlank(providerId, "providerId");
        spaceId = requireNonBlank(spaceId, "spaceId");
        modelRevision = requireNonBlank(modelRevision, "modelRevision");
        tokenizerRevision = requireNonBlank(tokenizerRevision, "tokenizerRevision");
        preprocessingRevision = requireNonBlank(preprocessingRevision, "preprocessingRevision");
        poolingStrategy = requireNonBlank(poolingStrategy, "poolingStrategy");
        if (vectorDimension < 0) throw new IllegalArgumentException("vectorDimension must not be negative");
        numericPrecision = requireNonBlank(numericPrecision, "numericPrecision");
        if (maxSequenceLength < 0) throw new IllegalArgumentException("maxSequenceLength must not be negative");
        chunkingStrategy = requireNonBlank(chunkingStrategy, "chunkingStrategy");
        chunkingRevision = requireNonBlank(chunkingRevision, "chunkingRevision");
        if (maxWindowTokens < 0) throw new IllegalArgumentException("maxWindowTokens must not be negative");
        if (windowOverlapTokens < 0) throw new IllegalArgumentException("windowOverlapTokens must not be negative");
        if (maxWindowTokens > 0 && windowOverlapTokens >= maxWindowTokens) {
            throw new IllegalArgumentException("windowOverlapTokens must be smaller than maxWindowTokens");
        }
        if (maxSequenceLength > 0 && maxWindowTokens > maxSequenceLength) {
            throw new IllegalArgumentException("maxWindowTokens must not exceed maxSequenceLength");
        }
        multiWindowAggregation = requireNonBlank(multiWindowAggregation, "multiWindowAggregation");
        fingerprint = requireNonBlank(fingerprint, "fingerprint");
        if (!fingerprint.equals(fingerprint(
                spaceId,
                modelRevision,
                tokenizerRevision,
                preprocessingRevision,
                poolingStrategy,
                l2Normalize,
                vectorDimension,
                numericPrecision,
                maxSequenceLength,
                chunkingStrategy,
                chunkingRevision,
                maxWindowTokens,
                windowOverlapTokens,
                multiWindowAggregation))) {
            throw new IllegalArgumentException("fingerprint does not match embedding-space fields");
        }
    }

    public static EmbeddingSpaceDescriptor providerBound(String providerId, String modelName) {
        String id = requireNonBlank(providerId, "providerId") + ":" + requireNonBlank(modelName, "modelName");
        return create(
                providerId,
                id,
                "unverified",
                "provider-managed",
                "provider-managed",
                "provider-managed",
                false,
                0,
                "FLOAT32",
                0,
                DEFAULT_CHUNKING_STRATEGY,
                DEFAULT_CHUNKING_REVISION,
                0,
                0,
                DEFAULT_MULTI_WINDOW_AGGREGATION);
    }

    public static EmbeddingSpaceDescriptor configured(String providerId, String spaceId, String revision) {
        return create(
                providerId,
                spaceId,
                revision,
                revision,
                "externally-verified",
                "externally-verified",
                false,
                0,
                "FLOAT32",
                0,
                DEFAULT_CHUNKING_STRATEGY,
                DEFAULT_CHUNKING_REVISION,
                0,
                0,
                DEFAULT_MULTI_WINDOW_AGGREGATION);
    }

    public static EmbeddingSpaceDescriptor verified(
            String providerId,
            String spaceId,
            String modelRevision,
            String tokenizerRevision,
            String preprocessingRevision,
            String poolingStrategy,
            boolean l2Normalize,
            int vectorDimension,
            String numericPrecision,
            int maxSequenceLength) {
        return verified(
                providerId,
                spaceId,
                modelRevision,
                tokenizerRevision,
                preprocessingRevision,
                poolingStrategy,
                l2Normalize,
                vectorDimension,
                numericPrecision,
                maxSequenceLength,
                DEFAULT_CHUNKING_STRATEGY,
                DEFAULT_CHUNKING_REVISION,
                maxSequenceLength,
                0,
                DEFAULT_MULTI_WINDOW_AGGREGATION);
    }

    public static EmbeddingSpaceDescriptor verified(
            String providerId,
            String spaceId,
            String modelRevision,
            String tokenizerRevision,
            String preprocessingRevision,
            String poolingStrategy,
            boolean l2Normalize,
            int vectorDimension,
            String numericPrecision,
            int maxSequenceLength,
            String chunkingStrategy,
            String chunkingRevision,
            int maxWindowTokens,
            int windowOverlapTokens,
            String multiWindowAggregation) {
        return create(
                providerId,
                spaceId,
                modelRevision,
                tokenizerRevision,
                preprocessingRevision,
                poolingStrategy,
                l2Normalize,
                vectorDimension,
                numericPrecision,
                maxSequenceLength,
                chunkingStrategy,
                chunkingRevision,
                maxWindowTokens,
                windowOverlapTokens,
                multiWindowAggregation);
    }

    public boolean isCompatibleWith(EmbeddingSpaceDescriptor other) {
        return other != null && fingerprint.equals(other.fingerprint);
    }

    private static EmbeddingSpaceDescriptor create(
            String providerId,
            String spaceId,
            String modelRevision,
            String tokenizerRevision,
            String preprocessingRevision,
            String poolingStrategy,
            boolean l2Normalize,
            int vectorDimension,
            String numericPrecision,
            int maxSequenceLength,
            String chunkingStrategy,
            String chunkingRevision,
            int maxWindowTokens,
            int windowOverlapTokens,
            String multiWindowAggregation) {
        return new EmbeddingSpaceDescriptor(
                providerId,
                spaceId,
                modelRevision,
                tokenizerRevision,
                preprocessingRevision,
                poolingStrategy,
                l2Normalize,
                vectorDimension,
                numericPrecision,
                maxSequenceLength,
                chunkingStrategy,
                chunkingRevision,
                maxWindowTokens,
                windowOverlapTokens,
                multiWindowAggregation,
                fingerprint(
                        spaceId,
                        modelRevision,
                        tokenizerRevision,
                        preprocessingRevision,
                        poolingStrategy,
                        l2Normalize,
                        vectorDimension,
                        numericPrecision,
                        maxSequenceLength,
                        chunkingStrategy,
                        chunkingRevision,
                        maxWindowTokens,
                        windowOverlapTokens,
                        multiWindowAggregation));
    }

    private static String fingerprint(
            String spaceId,
            String modelRevision,
            String tokenizerRevision,
            String preprocessingRevision,
            String poolingStrategy,
            boolean l2Normalize,
            int vectorDimension,
            String numericPrecision,
            int maxSequenceLength,
            String chunkingStrategy,
            String chunkingRevision,
            int maxWindowTokens,
            int windowOverlapTokens,
            String multiWindowAggregation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = String.join(
                    "\n",
                    spaceId,
                    modelRevision,
                    tokenizerRevision,
                    preprocessingRevision,
                    poolingStrategy,
                    Boolean.toString(l2Normalize),
                    Integer.toString(vectorDimension),
                    numericPrecision,
                    Integer.toString(maxSequenceLength),
                    chunkingStrategy,
                    chunkingRevision,
                    Integer.toString(maxWindowTokens),
                    Integer.toString(windowOverlapTokens),
                    multiWindowAggregation);
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
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
