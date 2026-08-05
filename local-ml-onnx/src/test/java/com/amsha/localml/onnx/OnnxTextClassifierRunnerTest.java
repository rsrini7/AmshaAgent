package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;

import org.junit.jupiter.api.Test;

class OnnxTextClassifierRunnerTest {

    @Test
    void selectsHighestSoftmaxScore() throws Exception {
        ClassifierResult<?> result = OnnxTextClassifierRunner.select(new float[][] {{0.0f, 2.0f, 1.0f}}, spec(0.5f));

        ClassifierResult.Opinion<?> opinion = assertInstanceOf(ClassifierResult.Opinion.class, result);
        assertEquals("researcher", opinion.value());
        assertEquals(0.6652f, opinion.confidence(), 0.0001f);
        assertEquals("intent-shadow-v1", opinion.ruleId());
    }

    @Test
    void returnsNoOpinionBelowCalibratedThreshold() throws Exception {
        ClassifierResult<?> result = OnnxTextClassifierRunner.select(new float[][] {{0.0f, 0.1f, 0.0f}}, spec(0.8f));

        ClassifierResult.NoOpinion<?> noOpinion = assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.LOW_CONFIDENCE, noOpinion.reason());
    }

    @Test
    void rejectsWrongShapeAndNonFiniteOutput() {
        assertThrows(
                OnnxClassifierInferenceException.class,
                () -> OnnxTextClassifierRunner.select(new float[] {1.0f, 2.0f, 3.0f}, spec(0.5f)));
        assertThrows(
                OnnxClassifierInferenceException.class,
                () -> OnnxTextClassifierRunner.select(new float[][] {{Float.NaN, 2.0f, 3.0f}}, spec(0.5f)));
    }

    private static OnnxClassifierBundleSpec spec(float minimumConfidence) {
        return new OnnxClassifierBundleSpec(
                "tokenizer-v1",
                "input_ids",
                "attention_mask",
                null,
                "logits",
                128,
                0L,
                List.of("coordinator", "researcher", "coder"),
                OnnxClassifierBundleSpec.ScoreTransform.SOFTMAX,
                minimumConfidence,
                "intent-shadow-v1",
                List.of("warm-up"));
    }
}
