package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;

import com.amsha.core.api.AgentSessionService;
import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierShadowDispatcher;
import com.amsha.core.classifier.ClassifierShadowExecutor;

import org.eclipse.microprofile.config.inject.ConfigProperty;

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
    private final AgentSessionService sessionService;
    private final TestDataCollectionService testDataCollectionService;
    private final boolean testDataCollectionEnabled;

    @Inject
    public ToolRiskShadowEvaluationService(
            Instance<ToolRiskShadowClassifier> classifiers,
            @Identifier("classifier-shadow") ClassifierShadowExecutor executor,
            MeterRegistry meterRegistry,
            AgentSessionService sessionService,
            TestDataCollectionService testDataCollectionService,
            @ConfigProperty(name = "amsha.test-data-collection.enabled", defaultValue = "false")
                    boolean testDataCollectionEnabled) {
        this(
                classifiers.stream().toList(),
                executor,
                meterRegistry,
                sessionService,
                testDataCollectionService,
                testDataCollectionEnabled);
    }

    ToolRiskShadowEvaluationService(
            List<ToolRiskShadowClassifier> classifiers,
            Executor executor,
            MeterRegistry meterRegistry,
            AgentSessionService sessionService,
            TestDataCollectionService testDataCollectionService,
            boolean testDataCollectionEnabled) {
        this.classifiers = classifiers.stream()
                .sorted(Comparator.comparing(classifier -> classifier.getClass().getName()))
                .toList();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.sessionService = sessionService;
        this.testDataCollectionService = testDataCollectionService;
        this.testDataCollectionEnabled = testDataCollectionEnabled;
        this.confidence = DistributionSummary.builder("amsha.classifier.tool_risk.shadow.confidence")
                .description("Confidence distribution for tool-risk shadow opinions")
                .serviceLevelObjectives(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
                .register(meterRegistry);
    }

    public void observeForDataCollection(String sessionId, ToolRiskShadowContext context) {
        maybeCollect(sessionId, context);
    }

    public void observeProvisionalAllow(String sessionId, ToolRiskShadowContext context) {
        maybeCollect(sessionId, context);
        ClassifierShadowDispatcher.observe(classifiers, executor, context, this::recordResult);
    }

    private void maybeCollect(String sessionId, ToolRiskShadowContext context) {
        if (!testDataCollectionEnabled || sessionService == null || testDataCollectionService == null) {
            return;
        }
        String expectedLabel = sessionService.getExpectedTestLabel(sessionId, "tool_risk");
        if (expectedLabel != null) {
            testDataCollectionService.recordToolRiskCandidate(expectedLabel, context);
        }
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
                .description("Payload-free tool-risk shadow results for provisional allows")
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
