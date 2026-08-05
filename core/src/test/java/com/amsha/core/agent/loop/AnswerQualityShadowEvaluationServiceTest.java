package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AnswerQualityShadowEvaluationServiceTest {

    @Test
    void recordsAcceptedComparisonWithoutExposingAnswerOrGoal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicReference<AnswerQualityShadowContext> observed = new AtomicReference<>();
        AnswerQualityShadowEvaluationService service = new AnswerQualityShadowEvaluationService(
                List.of(context -> {
                    observed.set(context);
                    return new ClassifierResult.Opinion<>(
                            AnswerQualityPrediction.ACCEPTABLE, 0.88f, "answer-quality-v1");
                }),
                Runnable::run,
                registry,
                null,
                null,
                false);
        AnswerQualityShadowContext context =
                new AnswerQualityShadowContext("researcher", "private goal", "private accepted answer", true);

        service.observeAccepted("test-session-id", context);

        assertEquals(context, observed.get());
        assertEquals(
                1.0,
                registry.get("amsha.classifier.answer_quality.shadow.results")
                        .tags(
                                "outcome",
                                "match",
                                "prediction",
                                "acceptable",
                                "reason",
                                "none",
                                "score_band",
                                "from_0_85_to_1")
                        .counter()
                        .count());
        assertFalse(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .anyMatch(tag -> tag.getValue().contains("private")
                        || tag.getValue().contains("researcher")
                        || tag.getValue().contains("answer-quality-v1")));
    }

    @Test
    void preservesExplicitNoOpinionReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnswerQualityShadowEvaluationService service = new AnswerQualityShadowEvaluationService(
                List.of(context -> new ClassifierResult.NoOpinion<>(NoOpinionReason.DISABLED)),
                Runnable::run,
                registry,
                null,
                null,
                false);

        service.observeAccepted("test-session-id", new AnswerQualityShadowContext("coder", "goal", "answer", false));

        assertEquals(
                1.0,
                registry.get("amsha.classifier.answer_quality.shadow.results")
                        .tags("outcome", "no_opinion", "reason", "disabled")
                        .counter()
                        .count());
    }
}
