package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.agent.loop.AnswerQualityPrediction;
import com.amsha.core.agent.loop.AnswerQualityShadowContext;
import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

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
    }

    private static AnswerQualityShadowContext context() {
        return new AnswerQualityShadowContext("researcher", "private goal", "private final answer", true);
    }
}
