package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class IntentShadowEvaluationServiceTest {

    @Test
    void recordsComparisonWithoutExposingPayloadOrChangingCallerState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicReference<String> observed = new AtomicReference<>();
        IntentShadowEvaluationService service = new IntentShadowEvaluationService(
                List.of(message -> {
                    observed.set(message);
                    return new ClassifierResult.Opinion<>("researcher", 0.92f, "intent-v1");
                }),
                Runnable::run,
                registry);

        service.observe("private user payload", "researcher");

        assertEquals("private user payload", observed.get());
        assertEquals(
                1.0,
                registry.get("amsha.classifier.intent.shadow.results")
                        .tags("outcome", "match", "reason", "none", "score_band", "from_0_85_to_1")
                        .counter()
                        .count());
        assertEquals(
                1,
                registry.get("amsha.classifier.intent.shadow.confidence")
                        .summary()
                        .count());
        assertFalse(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .anyMatch(tag -> tag.getValue().contains("private user payload")
                        || tag.getValue().contains("researcher")
                        || tag.getValue().contains("intent-v1")));
    }

    @Test
    void isolatesClassifierFailureAsNoOpinion() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntentShadowEvaluationService service = new IntentShadowEvaluationService(
                List.of(message -> {
                    throw new IllegalStateException("sensitive details");
                }),
                Runnable::run,
                registry);

        service.observe("private", "coordinator");

        assertEquals(
                1.0,
                registry.get("amsha.classifier.intent.shadow.results")
                        .tags("outcome", "no_opinion", "reason", "inference_error")
                        .counter()
                        .count());
    }

    @Test
    void recordsExplicitNoOpinionAndExecutorOverload() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntentShadowEvaluationService disabled = new IntentShadowEvaluationService(
                List.of(message -> new ClassifierResult.NoOpinion<>(NoOpinionReason.DISABLED)),
                Runnable::run,
                registry);
        disabled.observe("private", "coordinator");

        IntentShadowEvaluationService overloaded = new IntentShadowEvaluationService(
                List.of(message -> new ClassifierResult.Opinion<>("coordinator", 1.0f, "never-runs")),
                command -> {
                    throw new RejectedExecutionException();
                },
                registry);
        overloaded.observe("private", "coordinator");

        assertEquals(
                1.0,
                registry.get("amsha.classifier.intent.shadow.results")
                        .tags("outcome", "no_opinion", "reason", "disabled")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.get("amsha.classifier.intent.shadow.results")
                        .tags("outcome", "no_opinion", "reason", "overload_rejected")
                        .counter()
                        .count());
    }
}
