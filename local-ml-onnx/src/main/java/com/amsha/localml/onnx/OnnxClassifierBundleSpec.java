package com.amsha.localml.onnx;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Immutable graph, label, calibration, and warm-up contract for one text classifier. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnnxClassifierBundleSpec(
        String tokenizerRevision,
        String inputIdsName,
        String attentionMaskName,
        String tokenTypeIdsName,
        String outputName,
        int maxSequenceLength,
        Long paddingTokenId,
        List<String> labels,
        ScoreTransform scoreTransform,
        float minimumConfidence,
        String ruleId,
        List<String> warmupProbes)
        implements OnnxTextInputSpec {

    private static final int MAX_LABELS = 256;

    public enum ScoreTransform {
        SOFTMAX,
        SIGMOID
    }

    public OnnxClassifierBundleSpec {
        tokenizerRevision = requireNonBlank(tokenizerRevision, "tokenizerRevision");
        inputIdsName = requireTensorName(inputIdsName, "inputIdsName");
        attentionMaskName = requireTensorName(attentionMaskName, "attentionMaskName");
        if (tokenTypeIdsName != null) tokenTypeIdsName = requireTensorName(tokenTypeIdsName, "tokenTypeIdsName");
        outputName = requireTensorName(outputName, "outputName");
        if (maxSequenceLength <= 0) throw new IllegalArgumentException("maxSequenceLength must be positive");
        Objects.requireNonNull(paddingTokenId, "paddingTokenId");
        if (paddingTokenId < 0) throw new IllegalArgumentException("paddingTokenId must not be negative");
        labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
        if (labels.size() < 2 || labels.size() > MAX_LABELS) {
            throw new IllegalArgumentException("labels must contain between 2 and 256 entries");
        }
        for (String label : labels) requireNonBlank(label, "labels entry");
        if (new HashSet<>(labels).size() != labels.size()) {
            throw new IllegalArgumentException("labels must not contain duplicates");
        }
        Objects.requireNonNull(scoreTransform, "scoreTransform");
        if (!Float.isFinite(minimumConfidence) || minimumConfidence < 0.0f || minimumConfidence > 1.0f) {
            throw new IllegalArgumentException("minimumConfidence must be finite and in [0, 1]");
        }
        ruleId = requireNonBlank(ruleId, "ruleId");
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
