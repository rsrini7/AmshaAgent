package com.amsha.app.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Map;

import com.amsha.classifier.api.AnswerQualityPrediction;
import com.amsha.classifier.api.AnswerQualityShadowContext;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.core.classifier.DecisionAnswerQualityShadowClassifier;
import com.amsha.core.classifier.DecisionIntentShadowClassifier;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Real CDI discovery and injection integration test for shadow decision classifiers in Quarkus container.
 * Verifies real Arc resolution without mocks.
 */
@QuarkusTest
class DecisionShadowCdiWiringIT {

    @Inject
    Instance<DecisionIntentShadowClassifier> intentClassifierInstance;

    @Inject
    Instance<DecisionToolRiskShadowClassifier> toolRiskClassifierInstance;

    @Inject
    Instance<DecisionAnswerQualityShadowClassifier> answerQualityClassifierInstance;

    @Test
    void verifiesRealCdiDiscoveryAndDefaultDisabledResolution() {
        // Assert all 3 shadow classifiers are discoverable in CDI without mocks
        assertTrue(intentClassifierInstance.isResolvable(), "Intent shadow classifier bean must be resolvable via CDI");
        assertTrue(
                toolRiskClassifierInstance.isResolvable(),
                "Tool-risk shadow classifier bean must be resolvable via CDI");
        assertTrue(
                answerQualityClassifierInstance.isResolvable(),
                "Answer-quality shadow classifier bean must be resolvable via CDI");

        DecisionIntentShadowClassifier intentClassifier = intentClassifierInstance.get();
        assertNotNull(intentClassifier);
        // By default, amsha.decision.shadow.intent.enabled is false -> fails closed to DISABLED
        ClassifierResult<String> intentResult = intentClassifier.classify("Implement a feature in Java");
        assertInstanceOf(ClassifierResult.NoOpinion.class, intentResult);
        assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<String>) intentResult).reason());

        DecisionToolRiskShadowClassifier toolRiskClassifier = toolRiskClassifierInstance.get();
        assertNotNull(toolRiskClassifier);
        ToolRiskShadowContext riskCtx = new ToolRiskShadowContext(
                "coder", AutonomyLevel.FULL, "execute_shell", Map.of("cmd", "echo test"), "test");
        ClassifierResult<ToolRiskPrediction> riskResult = toolRiskClassifier.classify(riskCtx);
        assertInstanceOf(ClassifierResult.NoOpinion.class, riskResult);
        assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<ToolRiskPrediction>) riskResult).reason());

        DecisionAnswerQualityShadowClassifier qualityClassifier = answerQualityClassifierInstance.get();
        assertNotNull(qualityClassifier);
        AnswerQualityShadowContext qualityCtx =
                new AnswerQualityShadowContext("researcher", "explain DNS", "DNS translates names to IPs", false);
        ClassifierResult<AnswerQualityPrediction> qualityResult = qualityClassifier.classify(qualityCtx);
        assertInstanceOf(ClassifierResult.NoOpinion.class, qualityResult);
        assertEquals(
                NoOpinionReason.DISABLED,
                ((ClassifierResult.NoOpinion<AnswerQualityPrediction>) qualityResult).reason());
    }
}
