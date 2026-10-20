package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.IntentDecisionAction;
import com.amsha.classifier.api.IntentPolicyResolution;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Verifies approved activation properties are bound through the live Quarkus CDI router.
 *
 * <p>This is the positive control for {@code IntentDecisionRouterEmptyOwnerConfigIT}: the
 * configuration is identical except the rollout owner is named, and the gate opens.
 */
@QuarkusTest
@TestProfile(IntentDecisionRouterApprovedConfigIT.ApprovedProfile.class)
class IntentDecisionRouterApprovedConfigIT {

    private static final Set<String> ELIGIBLE_ROLES = Set.of("coder", "researcher", "coordinator");

    @Inject
    IntentDecisionRouter router;

    @Inject
    DecisionEngine engine;

    @Test
    void allApprovedConfigValuesOpenTheRouterGate() {
        assertTrue(router.isEnabled());
        assertTrue(router.isActivationGateApproved());
        assertTrue(router.isDirectRoutingPermitted());

        IntentPolicyResolution resolution = router.evaluateIntent("design an event taxonomy", ELIGIBLE_ROLES);
        assertEquals(IntentDecisionAction.DIRECT_ROUTE, resolution.action(), resolution.reason());
        assertEquals("coder", resolution.selectedRole().orElseThrow());
        verify(engine, times(1)).evaluate(any());
    }

    public static class ApprovedProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(IntentDecisionRouterTestSupport.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.routing.intent-decision-router.enabled", "true",
                    "amsha.routing.intent-decision-router.activation-gate.candidate-evidence-approved", "true",
                    "amsha.routing.intent-decision-router.activation-gate.cost-ceiling-approved", "true",
                    "amsha.routing.intent-decision-router.activation-gate.rollout-owner", "test-owner");
        }
    }
}
