package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;

import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierShadowDispatcher;
import com.amsha.core.classifier.ClassifierShadowExecutor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.common.annotation.Identifier;

/** Records answer-quality opinions without changing the accepted answer or retry budget. */
@ApplicationScoped
public class AnswerQualityShadowEvaluationService {

    private final List<AnswerQualityShadowClassifier> classifiers;
    private final Executor executor;
    private final MeterRegistry meterRegistry;
    private final DistributionSummary confidence;

    @Inject
    public AnswerQualityShadowEvaluationService(
            Instance<AnswerQualityShadowClassifier> classifiers,
            @Identifier("classifier-shadow") ClassifierShadowExecutor executor,
            MeterRegistry meterRegistry) {
        this(classifiers.stream().toList(), executor, meterRegistry);
    }

    AnswerQualityShadowEvaluationService(
            List<AnswerQualityShadowClassifier> classifiers, Executor executor, MeterRegistry meterRegistry) {
        this.classifiers = classifiers.stream()
                .sorted(Comparator.comparing(classifier -> classifier.getClass().getName()))
                .toList();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.confidence = DistributionSummary.builder("amsha.classifier.answer_quality.shadow.confidence")
                .description("Confidence distribution for Phase 2 answer-quality shadow opinions")
                .serviceLevelObjectives(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
                .register(meterRegistry);
    }

    public void observeAccepted(AnswerQualityShadowContext context) {
        ClassifierShadowDispatcher.observe(classifiers, executor, context, this::recordResult);
    }

    private void recordResult(ClassifierResult<AnswerQualityPrediction> result) {
        switch (result) {
            case ClassifierResult.Opinion<AnswerQualityPrediction> opinion -> {
                confidence.record(opinion.confidence());
                String prediction = opinion.value().name().toLowerCase(Locale.ROOT);
                recordOutcome(
                        opinion.value() == AnswerQualityPrediction.ACCEPTABLE ? "match" : "mismatch",
                        prediction,
                        "none",
                        scoreBand(opinion.confidence()));
            }
            case ClassifierResult.NoOpinion<AnswerQualityPrediction> noOpinion ->
                recordOutcome("no_opinion", "none", noOpinion.reason().name().toLowerCase(Locale.ROOT), "none");
        }
    }

    private void recordOutcome(String outcome, String prediction, String reason, String scoreBand) {
        Counter.builder("amsha.classifier.answer_quality.shadow.results")
                .description("Payload-free Phase 2 answer-quality shadow results for accepted answers")
                .tag("outcome", outcome)
                .tag("prediction", prediction)
                .tag("reason", reason)
                .tag("score_band", scoreBand)
                .register(meterRegistry)
                .increment();
    }

    private static String scoreBand(float score) {
        if (score < 0.5f) return "below_0_5";
        if (score < 0.85f) return "from_0_5_to_0_85";
        return "from_0_85_to_1";
    }
}
