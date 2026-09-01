package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.classifier.api.AnswerQualityPrediction;
import com.amsha.classifier.api.AnswerQualityShadowContext;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class OnnxAnswerQualityShadowClassifierTest {

    @Test
    void killSwitchAvoidsSerializationAndInference() {
        AtomicBoolean invoked = new AtomicBoolean();
        OnnxAnswerQualityShadowClassifier classifier =
                new OnnxAnswerQualityShadowClassifier(false, new ObjectMapper(), input -> {
                    invoked.set(true);
                    return new ClassifierResult.Opinion<>(AnswerQualityPrediction.ACCEPTABLE, 1.0f, "rule");
                });

        ClassifierResult<?> result = classifier.classify(context());

        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<?>) result).reason());
        assertFalse(invoked.get());
    }

    @Test
    void serializesToolBoundaryWithoutSessionOrUserIdentity() {
        AtomicReference<String> input = new AtomicReference<>();
        OnnxAnswerQualityShadowClassifier classifier =
                new OnnxAnswerQualityShadowClassifier(true, new ObjectMapper(), serialized -> {
                    input.set(serialized);
                    return new ClassifierResult.Opinion<>(AnswerQualityPrediction.RETRY_RECOMMENDED, 0.8f, "rule");
                });

        ClassifierResult<?> result = classifier.classify(context());

        assertInstanceOf(ClassifierResult.Opinion.class, result);
        assertTrue(input.get().contains("\"classifier\":\"answer_quality\""));
        assertTrue(input.get().contains("\"tool_invoked_in_turn\":true"));
        assertFalse(input.get().contains("session"));
        assertFalse(input.get().contains("user_id"));
    }

    @Test
    void parsesOnlyDeclaredBoundedLabels() {
        assertEquals(
                AnswerQualityPrediction.RETRY_RECOMMENDED,
                OnnxAnswerQualityShadowClassifier.parseLabel("retry recommended"));
        assertEquals(AnswerQualityPrediction.ACCEPTABLE, OnnxAnswerQualityShadowClassifier.parseLabel("acceptable"));
    }

    @Test
    void prepareAndRegisterReflectsReadinessCheck() {
        OnnxClassifierRuntimeControl control = Mockito.mock(OnnxClassifierRuntimeControl.class);
        OnnxClassifierRuntimeControl.Registration registration =
                Mockito.mock(OnnxClassifierRuntimeControl.Registration.class);
        Mockito.when(control.registerReadyImplementation(ArgumentMatchers.any()))
                .thenReturn(registration);

        OnnxAnswerQualityShadowClassifier readyClassifier =
                new OnnxAnswerQualityShadowClassifier(true, new ObjectMapper(), input -> null, () -> true);
        readyClassifier.prepareAndRegister(control);
        Mockito.verify(control).registerReadyImplementation(OnnxClassifierRuntimeControl.Classifier.ANSWER_QUALITY);

        OnnxAnswerQualityShadowClassifier notReadyClassifier =
                new OnnxAnswerQualityShadowClassifier(true, new ObjectMapper(), input -> null, () -> false);
        assertTrue(notReadyClassifier.prepareAndRegister(control).isEmpty());
    }

    @Test
    void classifyUnderLeaseHandlesExceptionsGracefully() {
        OnnxAnswerQualityShadowClassifier classifier =
                new OnnxAnswerQualityShadowClassifier(true, new ObjectMapper(), input -> {
                    throw new RuntimeException("Inference crash");
                });

        ClassifierResult<?> result = classifier.classifyUnderLease(context());
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.INFERENCE_ERROR, ((ClassifierResult.NoOpinion<?>) result).reason());
    }

    private static AnswerQualityShadowContext context() {
        return new AnswerQualityShadowContext("researcher", "private goal", "private final answer", true);
    }
}
