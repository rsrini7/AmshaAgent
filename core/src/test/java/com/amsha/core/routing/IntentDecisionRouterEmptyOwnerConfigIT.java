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
 * Isolates the rollout-owner variable from the two boolean approvals.
 *
 * <p>{@code IntentDecisionRouterPendingConfigIT} closes the gate with both approvals {@code false}, so
 * the empty owner cannot be the reason its assertion holds. Here both approvals are granted and only
 * the owner is blank — the shipped shape of that field
 * ({@code AMSHA_ROUTING_INTENT_DECISION_ROUTER_ACTIVATION_GATE_ROLLOUT_OWNER=}) — so the gate must stay
 * shut. {@code IntentDecisionRouterApprovedConfigIT} is the positive control: the same configuration
 * with a named owner opens the gate, which pins the owner as the deciding input.
 *
 * <p>It also proves the application boots when the property is present but empty, which is how the
 * shipped configuration sets it.
 */
@QuarkusTest
@TestProfile(IntentDecisionRouterEmptyOwnerConfigIT.EmptyOwnerProfile.class)
class IntentDecisionRouterEmptyOwnerConfigIT {

    private static final Set<String> ELIGIBLE_ROLES = Set.of("coder", "researcher", "coordinator");

    @Inject
    IntentDecisionRouter router;

    @Inject
    DecisionEngine engine;

    @Test
    void approvedBooleansWithBlankOwnerKeepTheGateShut() {
        assertTrue(router.isEnabled(), "router enable property and test DecisionEngine must resolve");
        assertFalse(
                router.isActivationGateApproved(),
                "a blank rollout owner must close the gate even when both approvals are granted");
        assertFalse(router.isDirectRoutingPermitted());

        IntentPolicyResolution resolution = router.evaluateIntent("design an event taxonomy", ELIGIBLE_ROLES);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, resolution.action());
        assertEquals("ACTIVATION_GATE_NOT_SATISFIED", resolution.reason());
        verify(engine, never()).evaluate(any());
    }

    public static class EmptyOwnerProfile implements QuarkusTestProfile {
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
                    "amsha.routing.intent-decision-router.activation-gate.rollout-owner", "");
        }
    }
}
