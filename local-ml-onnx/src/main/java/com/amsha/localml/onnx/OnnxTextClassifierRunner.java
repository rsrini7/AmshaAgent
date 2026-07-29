package com.amsha.localml.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.util.List;
import java.util.Objects;

import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;

/** Executes one verified text-classifier graph without logging or retaining its input. */
public final class OnnxTextClassifierRunner {

    public ClassifierResult<String> classify(
            OrtEnvironment environment,
            OrtSession session,
            HuggingFaceTokenizer tokenizer,
            OnnxClassifierBundleSpec spec,
            String text)
            throws OnnxClassifierInferenceException {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(tokenizer, "tokenizer");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(text, "text");

        OnnxTextInputSupport.Batch inputs;
        try {
            OnnxTextInputSupport.validateGraph(session, spec);
            inputs = OnnxTextInputSupport.prepare(tokenizer, spec, List.of(text));
        } catch (OnnxTextInputSupport.OnnxTextInputException | RuntimeException e) {
            throw new OnnxClassifierInferenceException("ONNX classifier input preparation failed", e);
        }

        try (OnnxTextInputSupport.TensorBatch tensors = OnnxTextInputSupport.createTensors(environment, spec, inputs);
                OrtSession.Result result = session.run(tensors.values())) {
            OnnxValue output = result.get(spec.outputName())
                    .orElseThrow(() -> new OnnxClassifierInferenceException("Declared ONNX output is missing"));
            return select(output.getValue(), spec);
        } catch (OrtException | RuntimeException e) {
            throw new OnnxClassifierInferenceException("ONNX classifier inference failed", e);
        }
    }

    static ClassifierResult<String> select(Object rawOutput, OnnxClassifierBundleSpec spec)
            throws OnnxClassifierInferenceException {
        if (!(rawOutput instanceof float[][] logits) || logits.length != 1) {
            throw new OnnxClassifierInferenceException("Declared classifier output must have shape [1, labels]");
        }
        if (logits[0].length != spec.labels().size()) {
            throw new OnnxClassifierInferenceException("Classifier output label count does not match manifest");
        }
        float[] scores = transform(logits[0], spec.scoreTransform());
        int selected = 0;
        for (int index = 1; index < scores.length; index++) {
            if (scores[index] > scores[selected]) selected = index;
        }
        float confidence = scores[selected];
        if (confidence < spec.minimumConfidence()) {
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.LOW_CONFIDENCE);
        }
        return new ClassifierResult.Opinion<>(spec.labels().get(selected), confidence, spec.ruleId());
    }

    private static float[] transform(float[] logits, OnnxClassifierBundleSpec.ScoreTransform transform)
            throws OnnxClassifierInferenceException {
        for (float logit : logits) {
            if (!Float.isFinite(logit)) {
                throw new OnnxClassifierInferenceException("Classifier output contains a non-finite value");
            }
        }
        return switch (transform) {
            case SOFTMAX -> softmax(logits);
            case SIGMOID -> sigmoid(logits);
        };
    }

    private static float[] softmax(float[] logits) throws OnnxClassifierInferenceException {
        float max = logits[0];
        for (int index = 1; index < logits.length; index++) max = Math.max(max, logits[index]);
        double denominator = 0;
        double[] exponentials = new double[logits.length];
        for (int index = 0; index < logits.length; index++) {
            exponentials[index] = Math.exp(logits[index] - max);
            denominator += exponentials[index];
        }
        if (!Double.isFinite(denominator) || denominator <= 0) {
            throw new OnnxClassifierInferenceException("Classifier softmax normalization failed");
        }
        float[] scores = new float[logits.length];
        for (int index = 0; index < logits.length; index++) scores[index] = (float) (exponentials[index] / denominator);
        return scores;
    }

    private static float[] sigmoid(float[] logits) {
        float[] scores = new float[logits.length];
        for (int index = 0; index < logits.length; index++) {
            scores[index] = (float) (1.0 / (1.0 + Math.exp(-logits[index])));
        }
        return scores;
    }
}
