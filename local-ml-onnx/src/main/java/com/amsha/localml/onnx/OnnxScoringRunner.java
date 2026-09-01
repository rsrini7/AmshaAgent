package com.amsha.localml.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Executes one verified cross-encoder scoring batch with manifest-declared pair inputs and output. */
public final class OnnxScoringRunner {

    private final OnnxInferenceMetrics metrics;

    public OnnxScoringRunner() {
        this(null);
    }

    public OnnxScoringRunner(OnnxInferenceMetrics metrics) {
        this.metrics = metrics;
    }

    public double[] scorePairs(
            OrtEnvironment environment,
            OnnxSessionLease lease,
            HuggingFaceTokenizer tokenizer,
            OnnxScoringBundleSpec spec,
            String query,
            List<String> candidateTexts)
            throws Exception {
        return scorePairs(environment, lease, tokenizer, spec, query, candidateTexts, null);
    }

    public double[] scorePairs(
            OrtEnvironment environment,
            OnnxSessionLease lease,
            HuggingFaceTokenizer tokenizer,
            OnnxScoringBundleSpec spec,
            String query,
            List<String> candidateTexts,
            Instant deadline)
            throws Exception {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(tokenizer, "tokenizer");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(candidateTexts, "candidateTexts");
        if (candidateTexts.isEmpty()) return new double[0];

        long started = System.nanoTime();
        OnnxTextInputSupport.Batch inputs;
        try {
            OrtSession session = lease.session();
            OnnxTextInputSupport.validateGraph(session, spec);
            inputs = OnnxTextInputSupport.preparePairs(tokenizer, spec, query, candidateTexts, metrics);
        } catch (OnnxTextInputSupport.OnnxTextInputException | RuntimeException e) {
            throw new Exception("ONNX scoring input preparation failed", e);
        }
        recordPhase("tokenization", started);

        started = System.nanoTime();
        try (OnnxTextInputSupport.TensorBatch tensors = OnnxTextInputSupport.createTensors(environment, spec, inputs)) {
            recordPhase("tensor_preparation", started);
            return runAndExtractScores(lease, spec, tensors.values(), candidateTexts.size(), deadline);
        } catch (OrtException | RuntimeException e) {
            throw new Exception("ONNX scoring inference failed", e);
        }
    }

    private double[] runAndExtractScores(
            OnnxSessionLease lease,
            OnnxScoringBundleSpec spec,
            Map<String, OnnxTensor> tensors,
            int expectedCount,
            Instant deadline)
            throws Exception {
        long started = System.nanoTime();
        try (OrtSession.Result result = lease.run(tensors, deadline)) {
            recordPhase("graph_execution", started);
            OnnxValue output =
                    result.get(spec.outputName()).orElseThrow(() -> new Exception("Declared ONNX output is missing"));
            started = System.nanoTime();
            double[] scores =
                    extractScores(output.getValue(), spec.expectedOutputShape(), spec.outputLabel(), expectedCount);
            recordPhase("extraction", started);
            return scores;
        }
    }

    static double[] extractScores(
            Object rawOutput, OnnxScoringBundleSpec.OutputShape shape, String outputLabel, int expectedCount)
            throws Exception {
        double[] scores = new double[expectedCount];
        switch (shape) {
            case BATCH_BY_ONE -> {
                if (!(rawOutput instanceof float[][] matrix) || matrix.length != expectedCount) {
                    throw new Exception("Scoring output must have shape [" + expectedCount + ", 1]");
                }
                for (int i = 0; i < expectedCount; i++) {
                    if (matrix[i] == null || matrix[i].length != 1 || !Float.isFinite(matrix[i][0])) {
                        throw new Exception(
                                "Scoring output contains invalid dimension or non-finite value at index " + i);
                    }
                    scores[i] = matrix[i][0];
                }
            }
            case ONE_DIMENSIONAL -> {
                if (!(rawOutput instanceof float[] vector) || vector.length != expectedCount) {
                    throw new Exception("Scoring output must have shape [" + expectedCount + "]");
                }
                for (int i = 0; i < expectedCount; i++) {
                    if (!Float.isFinite(vector[i])) {
                        throw new Exception("Scoring output contains invalid non-finite value at index " + i);
                    }
                    scores[i] = vector[i];
                }
            }
            case BATCH_BY_TWO -> {
                if (!(rawOutput instanceof float[][] matrix) || matrix.length != expectedCount) {
                    throw new Exception("Scoring output must have shape [" + expectedCount + ", 2]");
                }
                int targetColumn = resolveTargetColumn(outputLabel);
                for (int i = 0; i < expectedCount; i++) {
                    if (matrix[i] == null || matrix[i].length != 2 || !Float.isFinite(matrix[i][targetColumn])) {
                        throw new Exception(
                                "Scoring output contains invalid dimension or non-finite value at index " + i);
                    }
                    scores[i] = matrix[i][targetColumn];
                }
            }
        }
        return scores;
    }

    static int resolveTargetColumn(String outputLabel) throws Exception {
        if (outputLabel == null || outputLabel.isBlank()) {
            throw new Exception("outputLabel must not be null or blank for BATCH_BY_TWO scoring");
        }
        String trimmed = outputLabel.trim().toLowerCase(Locale.ROOT);
        return switch (trimmed) {
            case "0", "negative", "neutral", "contradiction" -> 0;
            case "1", "positive", "relevance", "entailment" -> 1;
            default -> {
                try {
                    int parsed = Integer.parseInt(trimmed);
                    if (parsed == 0 || parsed == 1) {
                        yield parsed;
                    }
                } catch (NumberFormatException _) {
                    // Fall through to error
                }
                throw new Exception(
                        "Unrecognized two-column outputLabel: '" + outputLabel
                                + "'. Must be 0, 1, 'relevance', 'positive', 'negative', 'neutral', 'entailment', or 'contradiction'");
            }
        };
    }

    private void recordPhase(String phase, long startedNanos) {
        long elapsedNanos = System.nanoTime() - startedNanos;
        if (metrics != null) metrics.recordScoringPhase(phase, elapsedNanos);
    }
}
