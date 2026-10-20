package com.amsha.app.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import jakarta.inject.Inject;

import java.util.Map;

import com.amsha.classifier.api.AnswerQualityShadowContext;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.core.classifier.DecisionAnswerQualityShadowClassifier;
import com.amsha.core.classifier.DecisionIntentShadowClassifier;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Pins the intentional Deferred behavior when a shadow decision classifier is enabled without a ready
 * {@code DecisionEngine}: every classifier must report {@code MISSING_MODEL} rather than silently
 * degrading to an allow.
 *
 * <p>This is a deliberate tripwire. The pilot is Deferred and no candidate engine is approved, so
 * enabled-without-engine is the only reachable path. If a production engine is ever registered these
 * assertions fail, forcing the Deferred status to be revisited on purpose instead of by accident.
 */
@QuarkusTest
@TestProfile(DecisionShadowDeferredStateIT.EnabledWithoutEngineProfile.class)
class DecisionShadowDeferredStateIT {

    @Inject
    DecisionIntentShadowClassifier intentClassifier;

    @Inject
    DecisionToolRiskShadowClassifier toolRiskClassifier;

    @Inject
    DecisionAnswerQualityShadowClassifier answerQualityClassifier;

    @Test
    void enabledIntentClassifierWithoutCandidateEngineReportsMissingModel() {
        assertMissingModel(intentClassifier.classify("route this request"));
    }

    @Test
    void enabledToolRiskClassifierWithoutCandidateEngineReportsMissingModel() {
        ToolRiskShadowContext context = new ToolRiskShadowContext(
                "coder",
                AutonomyLevel.FULL,
                "execute_shell",
                ToolOperation.EXECUTE_SHELL,
                Map.of("cmd", "echo test"),
                "test");

        assertMissingModel(toolRiskClassifier.classify(context));
    }

    @Test
    void enabledAnswerQualityClassifierWithoutCandidateEngineReportsMissingModel() {
        AnswerQualityShadowContext context =
                new AnswerQualityShadowContext("researcher", "explain DNS", "DNS translates names to IPs", false);

        assertMissingModel(answerQualityClassifier.classify(context));
    }

    private static <T> void assertMissingModel(ClassifierResult<T> result) {
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.MISSING_MODEL, ((ClassifierResult.NoOpinion<T>) result).reason());
    }

    /** Enables all three shadow classifiers while leaving the decision plane without an engine. */
    public static class EnabledWithoutEngineProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.decision.shadow.intent.enabled", "true",
                    "amsha.decision.shadow.tool-risk.enabled", "true",
                    "amsha.decision.shadow.answer-quality.enabled", "true");
        }
    }
}
