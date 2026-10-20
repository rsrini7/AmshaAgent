package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.PostPolicyToolContext;
import com.amsha.classifier.api.ToolRiskAction;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Classifier;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class OnnxPostPolicyToolRiskInterceptorTest {

    private static final PostPolicyToolContext CONTEXT = new PostPolicyToolContext(
            "coder",
            AutonomyLevel.FULL,
            "sandbox_exec",
            ToolOperation.EXECUTE_SHELL,
            Map.of("language", "java"),
            "run code",
            false);

    @Test
    void unconfiguredPassThroughWithoutOverhead() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        AtomicBoolean invoked = new AtomicBoolean();
        var shadowClassifier = new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(ToolRiskPrediction.LOW_RISK, 1.0f, "rule");
        });
        var interceptor = new OnnxPostPolicyToolRiskInterceptor(runtimeControl, shadowClassifier);

        ClassifierResult<ToolRiskAction> result = interceptor.evaluate(CONTEXT);

        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<?>) result).reason());
        assertFalse(invoked.get());
    }

    @Test
    void disabledPassesThrough() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        AtomicBoolean invoked = new AtomicBoolean();
        var shadowClassifier = new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(ToolRiskPrediction.LOW_RISK, 1.0f, "rule");
        });
        var interceptor = new OnnxPostPolicyToolRiskInterceptor(runtimeControl, shadowClassifier);

        try (var _ = runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK)) {
            ClassifierResult<ToolRiskAction> result = interceptor.evaluate(CONTEXT);

            assertInstanceOf(ClassifierResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<?>) result).reason());
            assertFalse(invoked.get());
        }
    }

    @Test
    void activeBehaviorMapsHighRiskToRequireApproval() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        AtomicBoolean invoked = new AtomicBoolean();
        var shadowClassifier = new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(ToolRiskPrediction.REQUIRE_APPROVAL, 0.9f, "rule");
        });
        var interceptor = new OnnxPostPolicyToolRiskInterceptor(runtimeControl, shadowClassifier);

        try (var _ = runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK)) {
            runtimeControl.setEnabled(Classifier.TOOL_RISK, true);
            ClassifierResult<ToolRiskAction> result = interceptor.evaluate(CONTEXT);

            assertInstanceOf(ClassifierResult.Opinion.class, result);
            assertEquals(ToolRiskAction.REQUIRE_APPROVAL, ((ClassifierResult.Opinion<?>) result).value());
            assertTrue(invoked.get());
        }
    }

    @Test
    void activeBehaviorMapsBlockToBlock() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        AtomicBoolean invoked = new AtomicBoolean();
        var shadowClassifier = new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(ToolRiskPrediction.BLOCK, 0.9f, "rule");
        });
        var interceptor = new OnnxPostPolicyToolRiskInterceptor(runtimeControl, shadowClassifier);

        try (var _ = runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK)) {
            runtimeControl.setEnabled(Classifier.TOOL_RISK, true);
            ClassifierResult<ToolRiskAction> result = interceptor.evaluate(CONTEXT);

            assertInstanceOf(ClassifierResult.Opinion.class, result);
            assertEquals(ToolRiskAction.BLOCK, ((ClassifierResult.Opinion<?>) result).value());
            assertTrue(invoked.get());
        }
    }

    @Test
    void forwardsCanonicalOperationThroughThePostPolicyPath() throws Exception {
        AtomicReference<String> serializedInput = new AtomicReference<>();
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        var shadowClassifier = new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), input -> {
            serializedInput.set(input);
            return new ClassifierResult.Opinion<>(ToolRiskPrediction.REQUIRE_APPROVAL, 0.9f, "rule");
        });
        var interceptor = new OnnxPostPolicyToolRiskInterceptor(runtimeControl, shadowClassifier);

        try (var _ = runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK)) {
            runtimeControl.setEnabled(Classifier.TOOL_RISK, true);
            interceptor.evaluate(CONTEXT);
        }

        assertEquals(
                ToolOperation.EXECUTE_SHELL.name(),
                new ObjectMapper()
                        .readTree(serializedInput.get())
                        .get(ToolRiskShadowContext.REQUIRED_OPERATION_INPUT_KEY)
                        .asText());
    }

    @Test
    void activeBehaviorMapsLowRiskToLowConfidence() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        AtomicBoolean invoked = new AtomicBoolean();
        var shadowClassifier = new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(ToolRiskPrediction.LOW_RISK, 0.9f, "rule");
        });
        var interceptor = new OnnxPostPolicyToolRiskInterceptor(runtimeControl, shadowClassifier);

        try (var _ = runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK)) {
            runtimeControl.setEnabled(Classifier.TOOL_RISK, true);
            ClassifierResult<ToolRiskAction> result = interceptor.evaluate(CONTEXT);

            assertInstanceOf(ClassifierResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.LOW_CONFIDENCE, ((ClassifierResult.NoOpinion<?>) result).reason());
            assertTrue(invoked.get());
        }
    }

    @Test
    void strictNonBlockingVirtualThreadSafetyFallbackOnException() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        var shadowClassifier = new OnnxToolRiskShadowClassifier(true, new ObjectMapper(), input -> {
            throw new RuntimeException("Inference failed");
        });
        var interceptor = new OnnxPostPolicyToolRiskInterceptor(runtimeControl, shadowClassifier);

        try (var _ = runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK)) {
            runtimeControl.setEnabled(Classifier.TOOL_RISK, true);
            ClassifierResult<ToolRiskAction> result = interceptor.evaluate(CONTEXT);

            assertInstanceOf(ClassifierResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.INFERENCE_ERROR, ((ClassifierResult.NoOpinion<?>) result).reason());
        }
    }
}
