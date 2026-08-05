package com.amsha.localml.onnx;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Immutable preprocessing and output contract for a transformer embedding bundle. This is
 * intentionally bundle metadata rather than application configuration so index compatibility is
 * tied to the verified artifact.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnnxEmbeddingBundleSpec(
        String tokenizerRevision,
        String inputIdsName,
        String attentionMaskName,
        String tokenTypeIdsName,
        String outputName,
        int maxSequenceLength,
        String chunkingStrategy,
        String chunkingRevision,
        int maxWindowTokens,
        int windowOverlapTokens,
        String multiWindowAggregation,
        int vectorDimension,
        Long paddingTokenId,
        PoolingStrategy poolingStrategy,
        boolean l2Normalize,
        String spaceId,
        String spaceRevision,
        List<String> warmupProbes)
        implements OnnxTextInputSpec {

    static final String SUPPORTED_CHUNKING_STRATEGY = "FIRST_MAX_TOKENS";
    static final String SUPPORTED_CHUNKING_REVISION = "v1";
    static final String SUPPORTED_MULTI_WINDOW_AGGREGATION = "DIRECT";

    public enum PoolingStrategy {
        CLS_TOKEN,
        MASKED_MEAN,
        DIRECT_VECTOR
    }

    public OnnxEmbeddingBundleSpec {
        tokenizerRevision = requireNonBlank(tokenizerRevision, "tokenizerRevision");
        inputIdsName = requireTensorName(inputIdsName, "inputIdsName");
        attentionMaskName = requireTensorName(attentionMaskName, "attentionMaskName");
        if (tokenTypeIdsName != null) tokenTypeIdsName = requireTensorName(tokenTypeIdsName, "tokenTypeIdsName");
        outputName = requireTensorName(outputName, "outputName");
        if (maxSequenceLength <= 0) throw new IllegalArgumentException("maxSequenceLength must be positive");
        chunkingStrategy = chunkingStrategy == null
                ? SUPPORTED_CHUNKING_STRATEGY
                : requireNonBlank(chunkingStrategy, "chunkingStrategy");
        chunkingRevision = chunkingRevision == null
                ? SUPPORTED_CHUNKING_REVISION
                : requireNonBlank(chunkingRevision, "chunkingRevision");
        if (maxWindowTokens == 0) maxWindowTokens = maxSequenceLength;
        if (maxWindowTokens != maxSequenceLength) {
            throw new IllegalArgumentException("maxWindowTokens must equal maxSequenceLength for FIRST_MAX_TOKENS");
        }
        if (windowOverlapTokens != 0) {
            throw new IllegalArgumentException("windowOverlapTokens must be zero for FIRST_MAX_TOKENS");
        }
        multiWindowAggregation = multiWindowAggregation == null
                ? SUPPORTED_MULTI_WINDOW_AGGREGATION
                : requireNonBlank(multiWindowAggregation, "multiWindowAggregation");
        if (!SUPPORTED_CHUNKING_STRATEGY.equals(chunkingStrategy)
                || !SUPPORTED_CHUNKING_REVISION.equals(chunkingRevision)
                || !SUPPORTED_MULTI_WINDOW_AGGREGATION.equals(multiWindowAggregation)) {
            throw new IllegalArgumentException(
                    "ONNX embeddings currently support only FIRST_MAX_TOKENS v1 with DIRECT aggregation");
        }
        if (vectorDimension <= 0) throw new IllegalArgumentException("vectorDimension must be positive");
        Objects.requireNonNull(paddingTokenId, "paddingTokenId");
        if (paddingTokenId < 0) throw new IllegalArgumentException("paddingTokenId must not be negative");
        Objects.requireNonNull(poolingStrategy, "poolingStrategy");
        spaceId = requireNonBlank(spaceId, "spaceId");
        spaceRevision = requireNonBlank(spaceRevision, "spaceRevision");
        warmupProbes = List.copyOf(Objects.requireNonNull(warmupProbes, "warmupProbes"));
        if (warmupProbes.isEmpty()) throw new IllegalArgumentException("warmupProbes must not be empty");
        for (String probe : warmupProbes) requireNonBlank(probe, "warmupProbes entry");
        rejectDuplicateNames(inputIdsName, attentionMaskName, tokenTypeIdsName, outputName);
    }

    private static void rejectDuplicateNames(
            String inputIdsName, String attentionMaskName, String tokenTypeIdsName, String outputName) {
        if (inputIdsName.equals(attentionMaskName)
                || inputIdsName.equals(tokenTypeIdsName)
                || inputIdsName.equals(outputName)
                || attentionMaskName.equals(tokenTypeIdsName)
                || attentionMaskName.equals(outputName)
                || (tokenTypeIdsName != null && tokenTypeIdsName.equals(outputName))) {
            throw new IllegalArgumentException("ONNX input and output names must be distinct");
        }
    }

    private static String requireTensorName(String value, String name) {
        String result = requireNonBlank(value, name);
        if (!result.matches("[A-Za-z_][A-Za-z0-9_./-]*")) {
            throw new IllegalArgumentException(name + " contains unsupported ONNX tensor-name characters");
        }
        return result;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
