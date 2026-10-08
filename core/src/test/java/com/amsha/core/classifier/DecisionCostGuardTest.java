package com.amsha.core.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Pins the cost guard's contract: it reports a rate only over a full window, it treats an unknown rate
 * as "not a breach" rather than as free, and it never exceeds its bounds.
 */
class DecisionCostGuardTest {

    private static final String LANE = "SELF_HOSTED";

    @Test
    void reportsNoRateUntilTheWindowIsFull() {
        DecisionCostGuard guard = new DecisionCostGuard(4);

        guard.record(LANE, 0.01);
        guard.record(LANE, 0.01);

        assertEquals(2, guard.windowSampleCount(LANE));
        assertTrue(guard.costPerThousand(LANE).isEmpty(), "a rate over a partial window is not a rate");
        assertFalse(guard.exceeded(LANE, new BigDecimal("0.001")));
    }

    @Test
    void reportsTheRollingRateOnceTheWindowIsFull() {
        DecisionCostGuard guard = new DecisionCostGuard(4);

        guard.record(LANE, 0.01);
        guard.record(LANE, 0.02);
        guard.record(LANE, 0.03);
        guard.record(LANE, 0.04);

        // Mean 0.025 per decision, so 25.0 per 1,000.
        assertEquals(25.0, guard.costPerThousand(LANE).orElseThrow().doubleValue(), 1e-9);
    }

    @Test
    void theWindowIsRollingSoAnOldExpensiveDecisionAgesOut() {
        DecisionCostGuard guard = new DecisionCostGuard(2);

        guard.record(LANE, 10.0); // an expensive outlier
        guard.record(LANE, 0.01);
        assertEquals(5005.0, guard.costPerThousand(LANE).orElseThrow().doubleValue(), 1e-6);

        guard.record(LANE, 0.01); // the outlier leaves the window
        assertEquals(10.0, guard.costPerThousand(LANE).orElseThrow().doubleValue(), 1e-9);
    }

    @Test
    void exceedsOnlyWhenARateIsKnownAndAboveTheCeiling() {
        DecisionCostGuard guard = new DecisionCostGuard(2);
        // The ceiling is itself expressed per 1,000 decisions, which is the unit the rolling rate is in.
        BigDecimal ceiling = new BigDecimal("20.0");

        // An unknown rate is not a breach: failing closed on no evidence would make the guard a shutdown
        // timer rather than a ceiling.
        assertFalse(guard.exceeded(LANE, ceiling));
        assertFalse(guard.exceeded(LANE, null), "with no approved ceiling nothing can be exceeded");

        guard.record(LANE, 0.01);
        guard.record(LANE, 0.01);
        assertFalse(guard.exceeded(LANE, ceiling), "10.0 per 1,000 is within a 20.0 ceiling");

        guard.record(LANE, 0.03);
        guard.record(LANE, 0.03);
        assertTrue(guard.exceeded(LANE, ceiling), "30.0 per 1,000 exceeds a 20.0 ceiling");
    }

    @Test
    void anUnpricedDecisionIsNotRecordedAsFree() {
        DecisionCostGuard guard = new DecisionCostGuard(2);

        guard.record(LANE, null);
        guard.record(LANE, null);

        assertEquals(0, guard.windowSampleCount(LANE), "an unknown cost must never become a zero cost");
        assertTrue(guard.costPerThousand(LANE).isEmpty());
    }

    @Test
    void ignoresABlankLaneAndBoundsTheTrackedLaneSpace() {
        DecisionCostGuard guard = new DecisionCostGuard(1);

        guard.record(null, 0.01);
        guard.record("  ", 0.01);
        assertEquals(0, guard.trackedLaneCount());

        for (int i = 0; i < DecisionCostGuard.MAX_LANES + 5; i++) {
            guard.record("lane-" + i, 0.01);
        }
        assertEquals(DecisionCostGuard.MAX_LANES, guard.trackedLaneCount());
    }

    @Test
    void aResetClearsTheWindow() {
        DecisionCostGuard guard = new DecisionCostGuard(1);

        guard.record(LANE, 0.01);
        assertTrue(guard.costPerThousand(LANE).isPresent());

        guard.reset(LANE);

        assertEquals(0, guard.windowSampleCount(LANE));
        assertTrue(guard.costPerThousand(LANE).isEmpty());
    }

    @Test
    void refusesAnImpossibleWindowOrNegativeCost() {
        assertThrows(IllegalArgumentException.class, () -> new DecisionCostGuard(0));
        assertThrows(IllegalArgumentException.class, () -> new DecisionCostGuard(2).record(LANE, -0.01));
    }
}
