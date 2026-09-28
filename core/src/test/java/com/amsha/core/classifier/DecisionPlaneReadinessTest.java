package com.amsha.core.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DecisionPlaneReadiness#deferredWarning}, the startup signal that reports an enabled
 * but engine-less decision plane.
 */
class DecisionPlaneReadinessTest {

    @Test
    void defaultConfigurationStaysSilent() {
        assertTrue(DecisionPlaneReadiness.deferredWarning(false, false, false, false, false)
                .isEmpty());
    }

    @Test
    void anEnabledSwitchWithoutAnEngineWarnsAndNamesIt() {
        Optional<String> warning = DecisionPlaneReadiness.deferredWarning(true, false, false, false, false);
        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains("amsha.decision.shadow.intent.enabled"));
        assertTrue(warning.get().contains("MISSING_MODEL"));
        assertTrue(warning.get().contains("no uniquely resolvable DecisionEngine bean"));
        assertFalse(warning.get().contains("tool-risk"), "only the enabled switch should be named");
    }

    @Test
    void everyEnabledSwitchIsNamed() {
        String warning = DecisionPlaneReadiness.deferredWarning(true, true, true, true, false)
                .orElseThrow();
        assertTrue(warning.contains("amsha.decision.shadow.intent.enabled"));
        assertTrue(warning.contains("amsha.decision.shadow.tool-risk.enabled"));
        assertTrue(warning.contains("amsha.decision.shadow.answer-quality.enabled"));
        assertTrue(warning.contains("amsha.routing.intent-decision-router.enabled"));
    }

    @Test
    void anEnabledSwitchWithAnEngineIsConsistentAndSilent() {
        // Control for the assertions above: if the warning depended only on the flags and ignored engine
        // availability, this would also warn, and the "inert" claim would be meaningless.
        assertTrue(DecisionPlaneReadiness.deferredWarning(true, true, true, true, true)
                .isEmpty());
    }

    @Test
    void warningExplainsTheConsequenceAndTheDeferredStatus() {
        String warning = DecisionPlaneReadiness.deferredWarning(false, true, false, false, false)
                .orElseThrow();
        assertTrue(warning.contains("ADR-jev-decision-plane"));
        assertTrue(warning.contains("Deferred"));
        assertEquals(1, warning.split("MISSING_MODEL", -1).length - 1, "states the fail-closed consequence once");
    }
}
