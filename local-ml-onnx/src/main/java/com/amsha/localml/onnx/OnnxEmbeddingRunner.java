package com.amsha.localml.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jboss.logging.Logger;

/** Executes one verified embedding batch with manifest-declared inputs, output, and pooling. */
public final class OnnxEmbeddingRunner {

    private static final Logger log = Logger.getLogger(OnnxEmbeddingRunner.class);

    private final OnnxInferenceMetrics metrics;

    public OnnxEmbeddingRunner() {
        this(null);
    }

    OnnxEmbeddingRunner(OnnxInferenceMetrics metrics) {
        this.metrics = metrics;
    }

    public List<float[]> embedAll(
            OrtEnvironment environment,
            OrtSession session,
            HuggingFaceTokenizer tokenizer,
            OnnxEmbeddingBundleSpec spec,
            List<String> texts)
            throws OnnxEmbeddingInferenceException {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(tokenizer, "tokenizer");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(texts, "texts");
        if (texts.isEmpty()) return List.of();
        for (String text : texts) Objects.requireNonNull(text, "texts must not contain null");
        validateGraph(session, spec);

        long started = System.nanoTime();
        BatchInputs inputs = tokenize(tokenizer, spec, texts);
        recordPhase("tokenization", started);
        started = System.nanoTime();
        try (OnnxTensor inputIds = OnnxTensor.createTensor(environment, inputs.inputIds());
                OnnxTensor attentionMask = OnnxTensor.createTensor(environment, inputs.attentionMask())) {
            Map<String, OnnxTensor> tensors = new LinkedHashMap<>();
            tensors.put(spec.inputIdsName(), inputIds);
            tensors.put(spec.attentionMaskName(), attentionMask);
            if (spec.tokenTypeIdsName() != null) {
                try (OnnxTensor tokenTypeIds = OnnxTensor.createTensor(environment, inputs.tokenTypeIds())) {
                    tensors.put(spec.tokenTypeIdsName(), tokenTypeIds);
                    recordPhase("tensor_preparation", started);
                    return runAndPool(session, spec, inputs.attentionMask(), tensors);
                }
            }
            recordPhase("tensor_preparation", started);
            return runAndPool(session, spec, inputs.attentionMask(), tensors);
        } catch (OrtException | RuntimeException e) {
            throw new OnnxEmbeddingInferenceException("ONNX embedding inference failed", e);
        }
    }

    private List<float[]> runAndPool(
            OrtSession session, OnnxEmbeddingBundleSpec spec, long[][] attentionMask, Map<String, OnnxTensor> tensors)
            throws OrtException, OnnxEmbeddingInferenceException {
        log.debug("ONNX embedding phase started: graph_execution");
        long started = System.nanoTime();
        try (OrtSession.Result result = session.run(tensors)) {
            recordPhase("graph_execution", started);
            OnnxValue output = result.get(spec.outputName())
                    .orElseThrow(() -> new OnnxEmbeddingInferenceException("Declared ONNX output is missing"));
            started = System.nanoTime();
            List<float[]> vectors = pool(output.getValue(), attentionMask, spec);
            recordPhase("pooling", started);
            return vectors;
        }
    }

    private void recordPhase(String phase, long startedNanos) {
        long elapsedNanos = System.nanoTime() - startedNanos;
        if (metrics != null) metrics.recordPhase(phase, elapsedNanos);
        log.debugf("ONNX embedding phase completed: phase=%s durationMs=%.3f", phase, elapsedNanos / 1_000_000.0);
    }

    private static void validateGraph(OrtSession session, OnnxEmbeddingBundleSpec spec)
            throws OnnxEmbeddingInferenceException {
        List<String> declaredInputs = new ArrayList<>();
        declaredInputs.add(spec.inputIdsName());
        declaredInputs.add(spec.attentionMaskName());
        if (spec.tokenTypeIdsName() != null) declaredInputs.add(spec.tokenTypeIdsName());
        if (!session.getInputNames().equals(Set.copyOf(declaredInputs))) {
            throw new OnnxEmbeddingInferenceException("ONNX graph inputs do not match the verified manifest");
        }
        if (!session.getOutputNames().contains(spec.outputName())) {
            throw new OnnxEmbeddingInferenceException("Declared ONNX output is absent from the graph");
        }
    }

    private static BatchInputs tokenize(
            HuggingFaceTokenizer tokenizer, OnnxEmbeddingBundleSpec spec, List<String> texts)
            throws OnnxEmbeddingInferenceException {
        Encoding[] encodings = tokenizer.batchEncode(texts, true, true);
        int sequenceLength = 0;
        for (Encoding encoding : encodings)
            sequenceLength = Math.max(sequenceLength, Math.min(encoding.getIds().length, spec.maxSequenceLength()));
        if (sequenceLength == 0) throw new OnnxEmbeddingInferenceException("Tokenizer produced an empty sequence");
        long[][] inputIds = new long[texts.size()][sequenceLength];
        long[][] attentionMask = new long[texts.size()][sequenceLength];
        long[][] tokenTypeIds = new long[texts.size()][sequenceLength];
        for (int row = 0; row < encodings.length; row++) {
            Arrays.fill(inputIds[row], spec.paddingTokenId());
            long[] ids = encodings[row].getIds();
            long[] mask = encodings[row].getAttentionMask();
            long[] types = encodings[row].getTypeIds();
            int copied = Math.min(ids.length, sequenceLength);
            System.arraycopy(ids, 0, inputIds[row], 0, copied);
            System.arraycopy(mask, 0, attentionMask[row], 0, Math.min(mask.length, copied));
            System.arraycopy(types, 0, tokenTypeIds[row], 0, Math.min(types.length, copied));
            if (countActive(attentionMask[row]) == 0)
                throw new OnnxEmbeddingInferenceException("Tokenizer produced an all-zero attention mask");
        }
        return new BatchInputs(inputIds, attentionMask, tokenTypeIds);
    }

    static List<float[]> pool(Object rawOutput, long[][] attentionMask, OnnxEmbeddingBundleSpec spec)
            throws OnnxEmbeddingInferenceException {
        if (rawOutput instanceof float[][] vectors) return directVectors(vectors, spec);
        if (rawOutput instanceof float[][][] tokenVectors) return tokenVectors(tokenVectors, attentionMask, spec);
        throw new OnnxEmbeddingInferenceException("Declared ONNX output must be float rank two or three");
    }

    private static List<float[]> directVectors(float[][] vectors, OnnxEmbeddingBundleSpec spec)
            throws OnnxEmbeddingInferenceException {
        if (spec.poolingStrategy() != OnnxEmbeddingBundleSpec.PoolingStrategy.DIRECT_VECTOR) {
            throw new OnnxEmbeddingInferenceException("Manifest pooling requires token-level ONNX output");
        }
        List<float[]> result = new ArrayList<>(vectors.length);
        for (float[] vector : vectors) result.add(validateAndNormalize(vector.clone(), spec));
        return result;
    }

    private static List<float[]> tokenVectors(
            float[][][] tokenVectors, long[][] attentionMask, OnnxEmbeddingBundleSpec spec)
            throws OnnxEmbeddingInferenceException {
        if (tokenVectors.length != attentionMask.length)
            throw new OnnxEmbeddingInferenceException("ONNX output batch size does not match input batch");
        if (spec.poolingStrategy() == OnnxEmbeddingBundleSpec.PoolingStrategy.DIRECT_VECTOR) {
            throw new OnnxEmbeddingInferenceException("Manifest direct-vector pooling requires rank-two ONNX output");
        }
        List<float[]> result = new ArrayList<>(tokenVectors.length);
        for (int row = 0; row < tokenVectors.length; row++) {
            float[] vector =
                    switch (spec.poolingStrategy()) {
                        case CLS_TOKEN -> cls(tokenVectors[row]);
                        case MASKED_MEAN -> maskedMean(tokenVectors[row], attentionMask[row], spec.vectorDimension());
                        case DIRECT_VECTOR -> throw new IllegalStateException("handled above");
                    };
            result.add(validateAndNormalize(vector, spec));
        }
        return result;
    }

    private static float[] cls(float[][] tokens) throws OnnxEmbeddingInferenceException {
        if (tokens.length == 0) throw new OnnxEmbeddingInferenceException("ONNX token output is empty");
        return tokens[0].clone();
    }

    private static float[] maskedMean(float[][] tokens, long[] mask, int dimension)
            throws OnnxEmbeddingInferenceException {
        if (tokens.length < mask.length)
            throw new OnnxEmbeddingInferenceException("ONNX token output is shorter than attention mask");
        float[] sum = new float[dimension];
        int count = 0;
        for (int token = 0; token < mask.length; token++) {
            if (mask[token] == 0) continue;
            if (tokens[token].length != dimension)
                throw new OnnxEmbeddingInferenceException("ONNX output dimension does not match manifest");
            for (int column = 0; column < dimension; column++) sum[column] += tokens[token][column];
            count++;
        }
        if (count == 0) throw new OnnxEmbeddingInferenceException("Cannot pool an all-zero attention mask");
        for (int column = 0; column < dimension; column++) sum[column] /= count;
        return sum;
    }

    private static float[] validateAndNormalize(float[] vector, OnnxEmbeddingBundleSpec spec)
            throws OnnxEmbeddingInferenceException {
        if (vector.length != spec.vectorDimension())
            throw new OnnxEmbeddingInferenceException("ONNX output dimension does not match manifest");
        double normSquared = 0;
        for (float value : vector) {
            if (!Float.isFinite(value))
                throw new OnnxEmbeddingInferenceException("ONNX output contains a non-finite value");
            normSquared += value * value;
        }
        if (!spec.l2Normalize()) return vector;
        if (normSquared == 0) throw new OnnxEmbeddingInferenceException("Cannot normalize a zero ONNX embedding");
        double norm = Math.sqrt(normSquared);
        for (int index = 0; index < vector.length; index++) vector[index] /= (float) norm;
        return vector;
    }

    private static int countActive(long[] mask) {
        int count = 0;
        for (long value : mask) if (value != 0) count++;
        return count;
    }

    private record BatchInputs(long[][] inputIds, long[][] attentionMask, long[][] tokenTypeIds) {}
}
