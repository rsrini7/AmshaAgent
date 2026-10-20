package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
 * Verifies real CDI engine discovery and pending approval config binding.
 *
 * <p>Both boolean approvals are {@code false} here, which alone closes the gate. The isolated
 * blank-owner case lives in {@code IntentDecisionRouterEmptyOwnerConfigIT}.
 */
@QuarkusTest
@TestProfile(IntentDecisionRouterPendingConfigIT.PendingProfile.class)
class IntentDecisionRouterPendingConfigIT {

    private static final Set<String> ELIGIBLE_ROLES = Set.of("coder", "researcher", "coordinator");

    @Inject
    IntentDecisionRouter router;

    @Inject
    DecisionEngine engine;

    @Test
    void pendingApprovalsRemainFailClosedAndSkipTheEngine() {
        assertTrue(router.isEnabled(), "router enable property and test DecisionEngine must resolve");
        assertFalse(router.isActivationGateApproved(), "pending approvals must close the gate");
        assertFalse(router.isDirectRoutingPermitted());

        IntentPolicyResolution resolution = router.evaluateIntent("write some python code", ELIGIBLE_ROLES);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, resolution.action());
        assertEquals("ACTIVATION_GATE_NOT_SATISFIED", resolution.reason());
        verify(engine, never()).evaluate(any());
    }

    public static class PendingProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(IntentDecisionRouterTestSupport.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.routing.intent-decision-router.enabled", "true",
                    "amsha.routing.intent-decision-router.activation-gate.candidate-evidence-approved", "false",
                    "amsha.routing.intent-decision-router.activation-gate.cost-ceiling-approved", "false",
                    "amsha.routing.intent-decision-router.activation-gate.rollout-owner", "");
        }
    }
}
