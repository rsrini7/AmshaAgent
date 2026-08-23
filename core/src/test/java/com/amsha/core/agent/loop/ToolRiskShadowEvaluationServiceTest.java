package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ToolRiskShadowEvaluationServiceTest {

    @Test
    void recordsPredictionWithoutExposingContextOrChangingCallerState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicReference<ToolRiskShadowContext> observed = new AtomicReference<>();
        ToolRiskShadowEvaluationService service = new ToolRiskShadowEvaluationService(
                List.of(context -> {
                    observed.set(context);
                    return new ClassifierResult.Opinion<>(ToolRiskPrediction.REQUIRE_APPROVAL, 0.91f, "tool-risk-v1");
                }),
                Runnable::run,
                registry,
                null,
                null,
                false);
        ToolRiskShadowContext context = new ToolRiskShadowContext(
                "coder", AutonomyLevel.FULL, "private_tool_name", Map.of("token", "private-secret"), "private goal");

        service.observeProvisionalAllow("test-session-id", context);

        assertEquals(context, observed.get());
        assertEquals(
                1.0,
                registry.get("amsha.classifier.tool_risk.shadow.results")
                        .tags(
                                "outcome",
                                "mismatch",
                                "prediction",
                                "require_approval",
                                "reason",
                                "none",
                                "score_band",
                                "from_0_85_to_1")
                        .counter()
                        .count());
        assertFalse(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .anyMatch(tag -> tag.getValue().contains("private")
                        || tag.getValue().contains("secret")
                        || tag.getValue().contains("tool-risk-v1")));
    }

    @Test
    void isolatesClassifierAndExecutorFailureAsBoundedNoOpinion() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ToolRiskShadowContext context =
                new ToolRiskShadowContext("coder", AutonomyLevel.FULL, "tool", Map.of(), "goal");
        new ToolRiskShadowEvaluationService(
                        List.of(ignored -> {
                            throw new IllegalStateException("sensitive");
                        }),
                        Runnable::run,
                        registry,
                        null,
                        null,
                        false)
                .observeProvisionalAllow("test-session-id", context);
        new ToolRiskShadowEvaluationService(
                        List.of(ignored -> new ClassifierResult.Opinion<>(ToolRiskPrediction.LOW_RISK, 1.0f, "never")),
                        command -> {
                            throw new RejectedExecutionException();
                        },
                        registry,
                        null,
                        null,
                        false)
                .observeProvisionalAllow("test-session-id", context);

        assertEquals(
                1.0,
                registry.get("amsha.classifier.tool_risk.shadow.results")
                        .tags("outcome", "no_opinion", "reason", "inference_error")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.get("amsha.classifier.tool_risk.shadow.results")
                        .tags("outcome", "no_opinion", "reason", "overload_rejected")
                        .counter()
                        .count());
    }
}
