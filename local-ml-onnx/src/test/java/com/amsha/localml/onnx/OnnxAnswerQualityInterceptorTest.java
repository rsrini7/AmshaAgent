package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import com.amsha.classifier.api.AnswerQualityAction;
import com.amsha.classifier.api.AnswerQualityContext;
import com.amsha.classifier.api.AnswerQualityPrediction;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Classifier;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class OnnxAnswerQualityInterceptorTest {

    private static final AnswerQualityContext CONTEXT =
            new AnswerQualityContext("researcher", "summarize", "A complete answer.", false);

    @Test
    void unconfiguredPassThroughWithoutOverhead() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        AtomicBoolean invoked = new AtomicBoolean();
        var shadowClassifier = new OnnxAnswerQualityShadowClassifier(true, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(AnswerQualityPrediction.ACCEPTABLE, 1.0f, "rule");
        });
        var interceptor = new OnnxAnswerQualityInterceptor(runtimeControl, shadowClassifier);

        ClassifierResult<AnswerQualityAction> result = interceptor.evaluate(CONTEXT);

        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<?>) result).reason());
        assertFalse(invoked.get());
    }

    @Test
    void disabledPassesThrough() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        AtomicBoolean invoked = new AtomicBoolean();
        var shadowClassifier = new OnnxAnswerQualityShadowClassifier(true, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(AnswerQualityPrediction.ACCEPTABLE, 1.0f, "rule");
        });
        var interceptor = new OnnxAnswerQualityInterceptor(runtimeControl, shadowClassifier);

        try (var _ = runtimeControl.registerReadyImplementation(Classifier.ANSWER_QUALITY)) {
            ClassifierResult<AnswerQualityAction> result = interceptor.evaluate(CONTEXT);

            assertInstanceOf(ClassifierResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<?>) result).reason());
            assertFalse(invoked.get());
        }
    }

    @Test
    void activeBehaviorMapsRetryRecommendedToRequestRetry() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        AtomicBoolean invoked = new AtomicBoolean();
        var shadowClassifier = new OnnxAnswerQualityShadowClassifier(true, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(AnswerQualityPrediction.RETRY_RECOMMENDED, 0.9f, "rule");
        });
        var interceptor = new OnnxAnswerQualityInterceptor(runtimeControl, shadowClassifier);

        try (var _ = runtimeControl.registerReadyImplementation(Classifier.ANSWER_QUALITY)) {
            runtimeControl.setEnabled(Classifier.ANSWER_QUALITY, true);
            ClassifierResult<AnswerQualityAction> result = interceptor.evaluate(CONTEXT);

            assertInstanceOf(ClassifierResult.Opinion.class, result);
            assertEquals(AnswerQualityAction.REQUEST_RETRY, ((ClassifierResult.Opinion<?>) result).value());
            assertTrue(invoked.get());
        }
    }

    @Test
    void activeBehaviorMapsAcceptableToLowConfidence() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        AtomicBoolean invoked = new AtomicBoolean();
        var shadowClassifier = new OnnxAnswerQualityShadowClassifier(true, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(AnswerQualityPrediction.ACCEPTABLE, 0.9f, "rule");
        });
        var interceptor = new OnnxAnswerQualityInterceptor(runtimeControl, shadowClassifier);

        try (var _ = runtimeControl.registerReadyImplementation(Classifier.ANSWER_QUALITY)) {
            runtimeControl.setEnabled(Classifier.ANSWER_QUALITY, true);
            ClassifierResult<AnswerQualityAction> result = interceptor.evaluate(CONTEXT);

            assertInstanceOf(ClassifierResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.LOW_CONFIDENCE, ((ClassifierResult.NoOpinion<?>) result).reason());
            assertTrue(invoked.get());
        }
    }

    @Test
    void strictNonBlockingVirtualThreadSafetyFallbackOnException() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        var shadowClassifier = new OnnxAnswerQualityShadowClassifier(true, new ObjectMapper(), input -> {
            throw new RuntimeException("Inference failed");
        });
        var interceptor = new OnnxAnswerQualityInterceptor(runtimeControl, shadowClassifier);

        try (var _ = runtimeControl.registerReadyImplementation(Classifier.ANSWER_QUALITY)) {
            runtimeControl.setEnabled(Classifier.ANSWER_QUALITY, true);
            ClassifierResult<AnswerQualityAction> result = interceptor.evaluate(CONTEXT);

            assertInstanceOf(ClassifierResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.INFERENCE_ERROR, ((ClassifierResult.NoOpinion<?>) result).reason());
        }
    }
}
