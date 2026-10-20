package com.amsha.app.classifier;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Test profiles for the decision evaluation path.
 *
 * <p>The fixture supplies the loopback provider endpoint. Enabled evaluation profiles also pin
 * the projection allow-list so evidence tests do not inherit a developer's local configuration.
 */
public final class DecisionEvaluationProfiles {

    private DecisionEvaluationProfiles() {}

    /** Enables the opt-in evaluation path so a real provider call can be exercised. */
    public static class EvaluationEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.decision.evaluation.enabled",
                    "true",
                    "amsha.decision.providers.slots.laya.governance.projection-allow-list",
                    "user_message,requiredOperation");
        }
    }

    /** Enables evaluation with a projection that deliberately omits the tool operation. */
    public static class EvaluationEnabledWithoutRequiredOperation implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.decision.evaluation.enabled", "true",
                    "amsha.decision.providers.slots.laya.governance.projection-allow-list", "user_message");
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
