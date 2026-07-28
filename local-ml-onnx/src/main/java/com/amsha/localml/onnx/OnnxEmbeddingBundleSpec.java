package com.amsha.localml.onnx;

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
        int vectorDimension,
        PoolingStrategy poolingStrategy,
        boolean l2Normalize,
        String spaceId,
        String spaceRevision) {

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
        if (vectorDimension <= 0) throw new IllegalArgumentException("vectorDimension must be positive");
        Objects.requireNonNull(poolingStrategy, "poolingStrategy");
        spaceId = requireNonBlank(spaceId, "spaceId");
        spaceRevision = requireNonBlank(spaceRevision, "spaceRevision");
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
