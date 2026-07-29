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

/** Records tool-risk opinions for provisional allows without changing the guard decision. */
@ApplicationScoped
public class ToolRiskShadowEvaluationService {

    private final List<ToolRiskShadowClassifier> classifiers;
    private final Executor executor;
    private final MeterRegistry meterRegistry;
    private final DistributionSummary confidence;

    @Inject
    public ToolRiskShadowEvaluationService(
            Instance<ToolRiskShadowClassifier> classifiers,
            @Identifier("classifier-shadow") ClassifierShadowExecutor executor,
            MeterRegistry meterRegistry) {
        this(classifiers.stream().toList(), executor, meterRegistry);
    }

    ToolRiskShadowEvaluationService(
            List<ToolRiskShadowClassifier> classifiers, Executor executor, MeterRegistry meterRegistry) {
        this.classifiers = classifiers.stream()
                .sorted(Comparator.comparing(classifier -> classifier.getClass().getName()))
                .toList();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.confidence = DistributionSummary.builder("amsha.classifier.tool_risk.shadow.confidence")
                .description("Confidence distribution for Phase 2 tool-risk shadow opinions")
                .serviceLevelObjectives(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
                .register(meterRegistry);
    }

    public void observeProvisionalAllow(ToolRiskShadowContext context) {
        ClassifierShadowDispatcher.observe(classifiers, executor, context, this::recordResult);
    }

    private void recordResult(ClassifierResult<ToolRiskPrediction> result) {
        switch (result) {
            case ClassifierResult.Opinion<ToolRiskPrediction> opinion -> {
                confidence.record(opinion.confidence());
                String prediction = opinion.value().name().toLowerCase(Locale.ROOT);
                recordOutcome(
                        opinion.value() == ToolRiskPrediction.LOW_RISK ? "match" : "mismatch",
                        prediction,
                        "none",
                        scoreBand(opinion.confidence()));
            }
            case ClassifierResult.NoOpinion<ToolRiskPrediction> noOpinion ->
                recordOutcome("no_opinion", "none", noOpinion.reason().name().toLowerCase(Locale.ROOT), "none");
        }
    }

    private void recordOutcome(String outcome, String prediction, String reason, String scoreBand) {
        Counter.builder("amsha.classifier.tool_risk.shadow.results")
                .description("Payload-free Phase 2 tool-risk shadow results for provisional allows")
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
