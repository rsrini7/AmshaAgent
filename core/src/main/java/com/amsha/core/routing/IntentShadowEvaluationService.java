package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.IntentShadowClassifier;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.classifier.ClassifierShadowDispatcher;
import com.amsha.core.classifier.ClassifierShadowExecutor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.common.annotation.Identifier;

/**
 * Runs optional intent classifiers asynchronously in shadow mode. It records only bounded metrics
 * and never returns or mutates an active routing decision.
 */
@ApplicationScoped
public class IntentShadowEvaluationService {

    private final List<IntentShadowClassifier> classifiers;
    private final Executor executor;
    private final MeterRegistry meterRegistry;
    private final DistributionSummary confidence;

    @Inject
    public IntentShadowEvaluationService(
            Instance<IntentShadowClassifier> classifiers,
            @Identifier("classifier-shadow") ClassifierShadowExecutor executor,
            MeterRegistry meterRegistry) {
        this(classifiers.stream().toList(), executor, meterRegistry);
    }

    IntentShadowEvaluationService(
            List<IntentShadowClassifier> classifiers, Executor executor, MeterRegistry meterRegistry) {
        this.classifiers = classifiers.stream()
                .sorted(Comparator.comparing(classifier -> classifier.getClass().getName()))
                .toList();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.confidence = DistributionSummary.builder("amsha.classifier.intent.shadow.confidence")
                .description("Confidence distribution for intent shadow opinions")
                .serviceLevelObjectives(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
                .register(meterRegistry);
    }

    /** Schedules privacy-safe comparison without delaying or replacing the authoritative route. */
    public void observe(String userMessage, String authoritativeRole) {
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(authoritativeRole, "authoritativeRole");
        ClassifierShadowDispatcher.observe(
                classifiers, executor, userMessage, result -> recordResult(result, authoritativeRole));
    }

    private void recordResult(ClassifierResult<String> result, String authoritativeRole) {
        switch (result) {
            case ClassifierResult.Opinion<String> opinion -> recordOpinion(opinion, authoritativeRole);
            case ClassifierResult.NoOpinion<String> noOpinion -> recordNoOpinion(noOpinion.reason());
        }
    }

    private void recordOpinion(ClassifierResult.Opinion<String> opinion, String authoritativeRole) {
        String predictedRole;
        try {
            predictedRole = AgentRoleEnum.fromString(opinion.value()).toScopedName();
        } catch (IllegalArgumentException e) {
            recordNoOpinion(NoOpinionReason.INFERENCE_ERROR);
            return;
        }
        confidence.record(opinion.confidence());
        recordOutcome(
                predictedRole.equals(authoritativeRole) ? "match" : "mismatch",
                "none",
                scoreBand(opinion.confidence()));
    }

    private void recordNoOpinion(NoOpinionReason reason) {
        recordOutcome("no_opinion", reason.name().toLowerCase(Locale.ROOT), "none");
    }

    private void recordOutcome(String outcome, String reason, String scoreBand) {
        Counter.builder("amsha.classifier.intent.shadow.results")
                .description("Privacy-safe intent shadow comparison results")
                .tag("outcome", outcome)
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
