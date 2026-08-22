package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.agent.loop.ToolRiskPrediction;
import com.amsha.core.agent.loop.ToolRiskShadowContext;
import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class OnnxToolRiskShadowClassifierTest {

    @Test
    void killSwitchAvoidsSerializationAndInference() {
        AtomicBoolean invoked = new AtomicBoolean();
        OnnxToolRiskShadowClassifier classifier = new OnnxToolRiskShadowClassifier(false, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(ToolRiskPrediction.LOW_RISK, 1.0f, "rule");
        });

        ClassifierResult<?> result = classifier.classify(context());

        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<?>) result).reason());
        assertFalse(invoked.get());
    }

    @Test
    void serializesOnlyBoundedContextEnvelopeForInProcessInference() {
        AtomicReference<String> input = new AtomicReference<>();
        OnnxToolRiskShadowClassifier classifier =
                new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), serialized -> {
                    input.set(serialized);
                    return new ClassifierResult.Opinion<>(ToolRiskPrediction.BLOCK, 0.9f, "rule");
                });

        ClassifierResult<?> result = classifier.classify(context());

        assertInstanceOf(ClassifierResult.Opinion.class, result);
        assertTrue(input.get().contains("\"classifier\":\"tool_risk\""));
        assertTrue(input.get().contains("\"tool_name\":\"sandbox_exec\""));
        assertTrue(input.get().contains("\"secret\":\"value\""));
        assertFalse(input.get().contains("session"));
        assertFalse(input.get().contains("user_id"));
    }

    @Test
    void parsesOnlyDeclaredBoundedLabels() {
        assertEquals(ToolRiskPrediction.REQUIRE_APPROVAL, OnnxToolRiskShadowClassifier.parseLabel("require-approval"));
    }

    private static ToolRiskShadowContext context() {
        return new ToolRiskShadowContext(
                "coder", AutonomyLevel.FULL, "sandbox_exec", Map.of("secret", "value"), "run the code");
    }
}
