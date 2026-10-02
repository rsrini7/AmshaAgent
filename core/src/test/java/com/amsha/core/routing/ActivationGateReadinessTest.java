package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ActivationGateReadiness#placeholderOwnerWarning}, the startup signal that reports a
 * satisfied activation gate whose rollout owner is a placeholder.
 */
class ActivationGateReadinessTest {

    @Test
    void aPartiallyApprovedGateIsSilent() {
        // The gate is a conjunction, so a placeholder owner cannot open it on its own. Warning here would
        // suggest a hazard that does not exist yet.
        assertTrue(ActivationGateReadiness.placeholderOwnerWarning(false, false, "TBD")
                .isEmpty());
        assertTrue(ActivationGateReadiness.placeholderOwnerWarning(true, false, "TBD")
                .isEmpty());
        assertTrue(ActivationGateReadiness.placeholderOwnerWarning(false, true, "TBD")
                .isEmpty());
    }

    @Test
    void aBlankOwnerIsTheHonestNotApprovedStateAndIsSilent() {
        // Blank leaves the gate closed, which is the correct Deferred posture — nothing to warn about.
        assertTrue(
                ActivationGateReadiness.placeholderOwnerWarning(true, true, "").isEmpty());
        assertTrue(ActivationGateReadiness.placeholderOwnerWarning(true, true, "   ")
                .isEmpty());
        assertTrue(ActivationGateReadiness.placeholderOwnerWarning(true, true, null)
                .isEmpty());
    }

    @Test
    void aPlaceholderOwnerOnASatisfiedGateWarnsAndNamesTheProperty() {
        Optional<String> warning = ActivationGateReadiness.placeholderOwnerWarning(true, true, "TBD");

        assertTrue(warning.isPresent());
        assertTrue(
                warning.get().contains("amsha.routing.intent-decision-router.activation-gate.rollout-owner"),
                "the operator needs the exact key to fix");
        assertTrue(warning.get().contains("'TBD'"), "the offending value is quoted back");
        assertTrue(warning.get().contains("NON-BLANK"), "the enforced rule is stated");
    }

    @Test
    void theWarningIsAdvisoryAndClaimsNoEnforcement() {
        String warning =
                ActivationGateReadiness.placeholderOwnerWarning(true, true, "x").orElseThrow();

        // The whole point of the choice not to validate: the message must not read as a rejection.
        assertTrue(warning.contains("Advisory only"));
        assertTrue(warning.contains("nothing is enabled, validated, or blocked"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "FALSE", "yes", "TBD", "todo", "n/a", "none", "null", "placeholder", "changeme"})
    void placeholderWordsAreRecognised(String owner) {
        assertTrue(
                ActivationGateReadiness.placeholderOwnerWarning(true, true, owner)
                        .isPresent(),
                owner + " should be recognised as a placeholder");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "?", "--", "xx", "??", "JD"})
    void veryShortValuesAreRecognised(String owner) {
        assertTrue(
                ActivationGateReadiness.placeholderOwnerWarning(true, true, owner)
                        .isPresent(),
                owner + " is too short to identify an accountable owner");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<owner>", "[TBD]", "(none)", "{team}", "---", "???", "xxx"})
    void templatedAndRepeatedValuesAreRecognised(String owner) {
        assertTrue(ActivationGateReadiness.placeholderOwnerWarning(true, true, owner)
                .isPresent());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "platform-ml@example.com",
                "@rsrini7",
                "team:platform-ml",
                "ops",
                "Jane Doe",
                "test-ops@example.com"
            })
    void genuineOwnersAreNotFlagged(String owner) {
        // The control for every assertion above. In particular `test-ops@example.com` contains the token
        // "test" but is not the token, which is why the match is whole-value and never a substring.
        assertTrue(
                ActivationGateReadiness.placeholderOwnerWarning(true, true, owner)
                        .isEmpty(),
                owner + " is a genuine owner and must not be flagged");
    }

    @Test
    void whitespaceIsTrimmedBeforeMatching() {
        assertTrue(ActivationGateReadiness.placeholderOwnerWarning(true, true, "  TBD  ")
                .isPresent());
        assertTrue(ActivationGateReadiness.placeholderOwnerWarning(true, true, "  platform-ml@example.com  ")
                .isEmpty());
    }

    @Test
    void theDiagnosticDoesNotCollideWithTheInertnessDiagnostic() {
        // Invariant §27.2 and JEV-12 assert DecisionPlaneReadiness logs exactly one WARN by counting log
        // lines that contain its logger name. A second warning emitted from that name would make the count
        // two and be reported as non-idempotent @Startup initialization, so the name must stay distinct.
        assertFalse(
                ActivationGateReadiness.class.getSimpleName().contains("DecisionPlaneReadiness"),
                "this diagnostic must log under its own name, not DecisionPlaneReadiness");
    }
}
