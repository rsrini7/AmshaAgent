package com.amsha.localml.onnx;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Immutable graph, tokenization, and output contract for a local ONNX cross-encoder scoring bundle.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnnxScoringBundleSpec(
        String inputType,
        String tokenizerRevision,
        String inputIdsName,
        String attentionMaskName,
        String tokenTypeIdsName,
        String outputName,
        int maxSequenceLength,
        Long paddingTokenId,
        String outputLabel,
        ScoreDirection scoreDirection,
        OutputShape expectedOutputShape,
        List<String> warmupProbes)
        implements OnnxTextInputSpec {

    public static final String REQUIRED_INPUT_TYPE = "text-pair";

    public enum ScoreDirection {
        HIGHER_IS_BETTER,
        LOWER_IS_BETTER
    }

    public enum OutputShape {
        BATCH_BY_ONE,
        ONE_DIMENSIONAL,
        BATCH_BY_TWO
    }

    public OnnxScoringBundleSpec {
        inputType = requireNonBlank(inputType, "inputType");
        if (!REQUIRED_INPUT_TYPE.equals(inputType)) {
            throw new IllegalArgumentException("inputType must be '" + REQUIRED_INPUT_TYPE + "'");
        }
        tokenizerRevision = requireNonBlank(tokenizerRevision, "tokenizerRevision");
        inputIdsName = requireTensorName(inputIdsName, "inputIdsName");
        attentionMaskName = requireTensorName(attentionMaskName, "attentionMaskName");
        if (tokenTypeIdsName != null) tokenTypeIdsName = requireTensorName(tokenTypeIdsName, "tokenTypeIdsName");
        outputName = requireTensorName(outputName, "outputName");
        if (maxSequenceLength <= 0) throw new IllegalArgumentException("maxSequenceLength must be positive");
        Objects.requireNonNull(paddingTokenId, "paddingTokenId");
        if (paddingTokenId < 0) throw new IllegalArgumentException("paddingTokenId must not be negative");
        outputLabel = requireNonBlank(outputLabel, "outputLabel");
        Objects.requireNonNull(scoreDirection, "scoreDirection");
        if (scoreDirection != ScoreDirection.HIGHER_IS_BETTER) {
            throw new IllegalArgumentException("scoreDirection must be 'HIGHER_IS_BETTER' for cross-encoder rerankers");
        }
        Objects.requireNonNull(expectedOutputShape, "expectedOutputShape");
        warmupProbes = List.copyOf(Objects.requireNonNull(warmupProbes, "warmupProbes"));
        if (warmupProbes.isEmpty()) throw new IllegalArgumentException("warmupProbes must not be empty");
        for (String probe : warmupProbes) requireNonBlank(probe, "warmupProbes entry");
        rejectDuplicateNames(inputIdsName, attentionMaskName, tokenTypeIdsName, outputName);
    }

    private static String requireTensorName(String value, String name) {
        String result = requireNonBlank(value, name);
        if (!result.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return result;
    }

    private static void rejectDuplicateNames(String inputIds, String mask, String tokenTypes, String output) {
        List<String> list =
                tokenTypes == null ? List.of(inputIds, mask, output) : List.of(inputIds, mask, tokenTypes, output);
        Set<String> names = new HashSet<>(list);
        if (names.size() != list.size()) {
            throw new IllegalArgumentException("Declared ONNX tensor names must be unique");
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
