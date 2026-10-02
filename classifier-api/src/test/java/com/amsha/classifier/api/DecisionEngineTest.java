package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DecisionEngine}'s default {@code category()}.
 *
 * <p>The default is not cosmetic. Invariant §27.4 requires {@code ProviderPlacementValidator} to admit only
 * an engine that declares {@link ProviderCategory#IN_PROCESS} behind a network-free classifier SPI, so a
 * provider that forgets to override {@code category()} is asserting that it is in-process. That makes the
 * default a claim about the deployment, and the override the thing that keeps a network-backed provider out.
 */
class DecisionEngineTest {

    private static DecisionResult noOpinionFor(DecisionRequest request) {
        return DecisionResult.NoOpinion.ofMalformedResponse(
                request.decisionId(), request.schemaVersion(), null, "not evaluated");
    }

    @Test
    void defaultCategoryIsInProcessAndEligibleForClassifierSpis() {
        // The lambda form also proves the seam is still a @FunctionalInterface with exactly one abstract
        // method: adding a second abstract method would stop this from compiling.
        DecisionEngine engine = DecisionEngineTest::noOpinionFor;

        assertSame(ProviderCategory.IN_PROCESS, engine.category());
        assertTrue(engine.category().permitsClassifierSpi());
    }

    @Test
    void aNetworkBackedEngineOverridesTheCategoryAndLosesSpiEligibility() {
        DecisionEngine engine = new DecisionEngine() {
            @Override
            public DecisionResult evaluate(DecisionRequest request) {
                return noOpinionFor(request);
            }

            @Override
            public ProviderCategory category() {
                return ProviderCategory.REMOTE;
            }
        };

        assertSame(ProviderCategory.REMOTE, engine.category());
        assertFalse(
                engine.category().permitsClassifierSpi(),
                "a remote engine must not be admitted behind a network-free classifier SPI");
    }
}
