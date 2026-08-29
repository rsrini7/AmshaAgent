package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
        assertEquals(ToolRiskPrediction.LOW_RISK, OnnxToolRiskShadowClassifier.parseLabel("low risk"));
        assertEquals(ToolRiskPrediction.BLOCK, OnnxToolRiskShadowClassifier.parseLabel("block"));
    }

    @Test
    void prepareAndRegisterReflectsReadinessCheck() {
        OnnxClassifierRuntimeControl control = Mockito.mock(OnnxClassifierRuntimeControl.class);
        OnnxClassifierRuntimeControl.Registration registration =
                Mockito.mock(OnnxClassifierRuntimeControl.Registration.class);
        Mockito.when(control.registerReadyImplementation(any())).thenReturn(registration);

        OnnxToolRiskShadowClassifier readyClassifier =
                new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), input -> null, () -> true);
        readyClassifier.prepareAndRegister(control);
        Mockito.verify(control).registerReadyImplementation(OnnxClassifierRuntimeControl.Classifier.TOOL_RISK);

        OnnxToolRiskShadowClassifier notReadyClassifier =
                new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), input -> null, () -> false);
        assertTrue(notReadyClassifier.prepareAndRegister(control).isEmpty());
    }

    @Test
    void classifyUnderLeaseHandlesExceptionsGracefully() {
        OnnxToolRiskShadowClassifier classifier = new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), input -> {
            throw new RuntimeException("Inference crash");
        });

        ClassifierResult<?> result = classifier.classifyUnderLease(context());
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.INFERENCE_ERROR, ((ClassifierResult.NoOpinion<?>) result).reason());
    }

    private static ToolRiskShadowContext context() {
        return new ToolRiskShadowContext(
                "coder", AutonomyLevel.FULL, "sandbox_exec", Map.of("secret", "value"), "run the code");
    }
}
