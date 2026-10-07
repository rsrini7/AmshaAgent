package com.amsha.app.classifier;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Test profiles for the decision evaluation path.
 *
 * <p>They set only {@code amsha.decision.evaluation.enabled}; the provider slot itself comes from
 * {@link DecisionProviderFixture}, so the enabled and disabled cases differ by exactly the switch
 * under test.
 */
public final class DecisionEvaluationProfiles {

    private DecisionEvaluationProfiles() {}

    /** Enables the opt-in evaluation path so a real provider call can be exercised. */
    public static class EvaluationEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("amsha.decision.evaluation.enabled", "true");
        }
    }

    /** Keeps the opt-in evaluation path off — the shipped, no-egress posture. */
    public static class EvaluationDisabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("amsha.decision.evaluation.enabled", "false");
        }
    }
}
