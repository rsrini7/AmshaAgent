package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.PostPolicyToolContext;
import com.amsha.classifier.api.ToolRiskAction;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Classifier;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** Integration-style: enable after readiness then classify under lease. */
class OnnxClassifierEnableClassifyIT {

    @Test
    void enableAfterReadinessAllowsImmediateClassifyEvenWhenBootShadowDisabled() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        AtomicBoolean invoked = new AtomicBoolean();
        // Boot shadow enabled=false; active path must still classify under lease.
        var shadowClassifier = new OnnxToolRiskShadowClassifier(false, new ObjectMapper(), input -> {
            invoked.set(true);
            return new ClassifierResult.Opinion<>(ToolRiskPrediction.REQUIRE_APPROVAL, 0.95f, "it-rule");
        });
        var interceptor = new OnnxPostPolicyToolRiskInterceptor(runtimeControl, shadowClassifier);

        assertTrue(shadowClassifier.prepareAndRegister(runtimeControl).isPresent());
        assertTrue(runtimeControl.setEnabled(Classifier.TOOL_RISK, true).accepted());

        ClassifierResult<ToolRiskAction> result = interceptor.evaluate(new PostPolicyToolContext(
                "coder",
                AutonomyLevel.FULL,
                "sandbox_exec",
                ToolOperation.EXECUTE_SHELL,
                Map.of("language", "java"),
                "run",
                false));

        assertInstanceOf(ClassifierResult.Opinion.class, result);
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, ((ClassifierResult.Opinion<?>) result).value());
        assertTrue(invoked.get());
    }

    @Test
    void failedReadinessNeverRegistersReady() {
        var runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
        var shadowClassifier = new OnnxToolRiskShadowClassifier(
                false,
                new ObjectMapper(),
                input -> {
                    throw new AssertionError("should not classify");
                },
                () -> false);

        assertTrue(shadowClassifier.prepareAndRegister(runtimeControl).isEmpty());
        assertTrue(
                runtimeControl.setEnabled(Classifier.TOOL_RISK, true).reason().equals("MISSING_READY_IMPLEMENTATION"));
    }
}
