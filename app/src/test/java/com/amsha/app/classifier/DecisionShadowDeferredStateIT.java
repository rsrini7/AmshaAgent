package com.amsha.app.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import jakarta.inject.Inject;

import java.util.Map;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.core.classifier.DecisionIntentShadowClassifier;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/** Pins the intentional Deferred behavior when shadow intent is enabled without a ready engine. */
@QuarkusTest
@TestProfile(DecisionShadowDeferredStateIT.EnabledWithoutEngineProfile.class)
class DecisionShadowDeferredStateIT {
    @Inject
    DecisionIntentShadowClassifier intentClassifier;

    @Test
    void enabledClassifierWithoutCandidateEngineReportsMissingModel() {
        ClassifierResult<String> result = intentClassifier.classify("route this request");

        ClassifierResult.NoOpinion<String> noOpinion = assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.MISSING_MODEL, noOpinion.reason());
    }

    public static class EnabledWithoutEngineProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("amsha.decision.shadow.intent.enabled", "true");
        }
    }
}
