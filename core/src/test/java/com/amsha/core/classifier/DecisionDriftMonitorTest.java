package com.amsha.core.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;

import com.amsha.classifier.api.DecisionLatencyBudget;
import com.amsha.classifier.api.ProviderCategory;

import org.junit.jupiter.api.Test;

/**
 * Pins the drift monitor's contract: it is bounded, it compares a current window against a baseline,
 * it alerts once per episode, and it never changes a decision.
 */
class DecisionDriftMonitorTest {

    private static final String LANE = "SELF_HOSTED";

    private static DecisionDriftMonitor monitor(int window) {
        return new DecisionDriftMonitor(window, 0.15, 0.20);
    }

    private static void fill(DecisionDriftMonitor monitor, String lane, int count, boolean opinion, Double confidence) {
        for (int i = 0; i < count; i++) {
            monitor.observe(lane, opinion, confidence, Duration.ofMillis(5));
        }
    }

    @Test
    void aStableLaneDoesNotDrift() {
        DecisionDriftMonitor monitor = monitor(4);

        fill(monitor, LANE, 4, true, 0.90); // baseline
        fill(monitor, LANE, 4, true, 0.90); // current, identical

        assertFalse(monitor.drifted(LANE));
        assertEquals(0.0, monitor.confidenceDelta(LANE), 1e-9);
        assertFalse(monitor.alertEmitted(LANE));
    }

    @Test
    void aConfidenceShiftBeyondTheThresholdDriftsAndAlertsOnce() {
        DecisionDriftMonitor monitor = monitor(4);

        fill(monitor, LANE, 4, true, 0.95); // baseline mean 0.95
        fill(monitor, LANE, 4, true, 0.40); // current mean 0.40, a 0.55 move

        assertTrue(monitor.drifted(LANE));
        assertEquals(0.55, monitor.confidenceDelta(LANE), 1e-9);
        assertTrue(monitor.alertEmitted(LANE));

        // A sustained drift is one alert, not one per decision: the latch holds until a reset.
        fill(monitor, LANE, 4, true, 0.40);
        assertTrue(monitor.alertEmitted(LANE));
    }

    @Test
    void aRecoveredDriftCanAlertAgainAsANewEpisode() {
        DecisionDriftMonitor monitor = monitor(4);
        fill(monitor, LANE, 4, true, 0.95);
        fill(monitor, LANE, 4, true, 0.40);
        assertTrue(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.DRIFT));

        fill(monitor, LANE, 4, true, 0.95);
        assertFalse(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.DRIFT));

        fill(monitor, LANE, 4, true, 0.40);
        assertTrue(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.DRIFT));
    }

    @Test
    void anAbstentionShiftBeyondTheThresholdDrifts() {
        DecisionDriftMonitor monitor = monitor(4);

        fill(monitor, LANE, 4, true, 0.95); // baseline: no abstentions
        for (int i = 0; i < 4; i++) {
            monitor.observe(LANE, false, null, Duration.ofMillis(5)); // current: 100% abstention
        }

        assertTrue(monitor.drifted(LANE));
        assertEquals(1.0, monitor.abstentionDelta(LANE), 1e-9);
    }

    @Test
    void aPartialBaselineNeverDrifts() {
        DecisionDriftMonitor monitor = monitor(8);

        // Only five observations, so neither window is full: comparing against a partial baseline would
        // alert on the first few decisions, which is noise rather than drift.
        fill(monitor, LANE, 5, true, 0.95);

        assertFalse(monitor.drifted(LANE));
        assertEquals(0.0, monitor.confidenceDelta(LANE), 1e-9);
    }

    @Test
    void anUnobservedLaneIsNotADrift() {
        DecisionDriftMonitor monitor = monitor(4);

        assertFalse(monitor.drifted(LANE));
        assertFalse(monitor.drifted(null));
        assertFalse(monitor.drifted("   "));
        assertEquals(0.0, monitor.confidenceDelta("never-seen"), 1e-9);
        assertEquals(0L, monitor.p95LatencyNanos("never-seen"));
    }

    @Test
    void aLatencyBreachAlertsAndIsReported() {
        DecisionDriftMonitor monitor = monitor(4);

        fill(monitor, LANE, 4, true, 0.9); // baseline
        for (int i = 0; i < 4; i++) {
            monitor.observe(
                    LANE, true, 0.9, Duration.ofMillis(2_500)); // 2,500 ms, above the 2-second self-hosted model budget
        }

        assertEquals(Duration.ofMillis(2_500).toNanos(), monitor.p95LatencyNanos(LANE));
        assertTrue(monitor.checkLatency(LANE, ProviderCategory.SELF_HOSTED, DecisionLatencyBudget.ADR_8_3));
        assertTrue(monitor.alertEmitted(LANE));
    }

    @Test
    void aRelativeLatencyRegressionAlertsBelowTheAbsoluteBudgetAndClearsAfterRecovery() {
        DecisionDriftMonitor monitor = monitor(4);
        observeLatency(monitor, Duration.ofMillis(900), 4);
        observeLatency(monitor, Duration.ofMillis(1_900), 4);

        assertEquals(Duration.ofMillis(1_900).toNanos(), monitor.p95LatencyNanos(LANE));
        assertFalse(monitor.checkLatency(LANE, ProviderCategory.SELF_HOSTED, DecisionLatencyBudget.ADR_8_3));
        assertTrue(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.LATENCY_DRIFT));
        assertFalse(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.LATENCY));

        observeLatency(monitor, Duration.ofMillis(900), 4);
        assertFalse(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.LATENCY_DRIFT));
    }

    @Test
    void aLatencyIncreaseBelowTwiceTheBaselineDoesNotTriggerRelativeAlert() {
        DecisionDriftMonitor monitor = monitor(4);
        observeLatency(monitor, Duration.ofMillis(1_000), 4);
        observeLatency(monitor, Duration.ofMillis(1_900), 4);

        assertFalse(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.LATENCY_DRIFT));
    }

    @Test
    void aRecoveredLatencyBreachClearsItsAlertLatch() {
        DecisionDriftMonitor monitor = monitor(4);
        fill(monitor, LANE, 4, true, 0.9);
        for (int i = 0; i < 4; i++) {
            monitor.observe(LANE, true, 0.9, Duration.ofMillis(2_500));
        }
        assertTrue(monitor.checkLatency(LANE, ProviderCategory.SELF_HOSTED, DecisionLatencyBudget.ADR_8_3));
        assertTrue(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.LATENCY));

        for (int i = 0; i < 4; i++) {
            monitor.observe(LANE, true, 0.9, Duration.ofMillis(5));
        }
        assertFalse(monitor.checkLatency(LANE, ProviderCategory.SELF_HOSTED, DecisionLatencyBudget.ADR_8_3));
        assertFalse(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.LATENCY));
    }

    @Test
    void aLatencyWithinBudgetDoesNotAlert() {
        DecisionDriftMonitor monitor = monitor(4);

        fill(monitor, LANE, 8, true, 0.9); // 5 ms per observation, well inside the local budget

        assertFalse(monitor.checkLatency(LANE, ProviderCategory.SELF_HOSTED, DecisionLatencyBudget.ADR_8_3));
        assertFalse(monitor.alertEmitted(LANE));
    }

    @Test
    void aResetClearsTheWindowsAndTheAlertLatch() {
        DecisionDriftMonitor monitor = monitor(4);

        fill(monitor, LANE, 4, true, 0.95);
        fill(monitor, LANE, 4, true, 0.40);
        assertTrue(monitor.alertEmitted(LANE));

        monitor.reset(LANE);

        assertFalse(monitor.alertEmitted(LANE));
        assertFalse(monitor.drifted(LANE));
        assertEquals(0, monitor.trackedLaneCount());
    }

    @Test
    void theTrackedLaneSpaceIsBounded() {
        DecisionDriftMonitor monitor = monitor(4);

        for (int i = 0; i < DecisionDriftMonitor.MAX_LANES + 5; i++) {
            monitor.observe("lane-" + i, true, 0.9, Duration.ofMillis(5));
        }

        assertEquals(DecisionDriftMonitor.MAX_LANES, monitor.trackedLaneCount());
    }

    @Test
    void theCostCeilingAlertIsLatchedPerLane() {
        DecisionDriftMonitor monitor = monitor(4);

        monitor.alertCostCeiling(LANE, new BigDecimal("0.05"));
        assertTrue(monitor.alertEmitted(LANE));

        monitor.resetAll();
        assertFalse(monitor.alertEmitted(LANE));
    }

    @Test
    void aRecoveredCostCeilingAlertCanBeIssuedAgain() {
        DecisionDriftMonitor monitor = monitor(4);
        monitor.alertCostCeiling(LANE, new BigDecimal("0.05"));
        assertTrue(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.COST_CEILING));

        monitor.clearAlert(LANE, DecisionDriftMonitor.AlertKind.COST_CEILING);
        assertFalse(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.COST_CEILING));

        monitor.alertCostCeiling(LANE, new BigDecimal("0.05"));
        assertTrue(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.COST_CEILING));
    }

    @Test
    void alertsOfDifferentKindsOnTheSameLaneDoNotSuppressEachOther() {
        DecisionDriftMonitor monitor = monitor(4);

        fill(monitor, LANE, 4, true, 0.95); // baseline
        fill(monitor, LANE, 4, true, 0.40); // current: a confidence drift, so the DRIFT latch is set

        assertTrue(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.DRIFT));
        assertFalse(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.LATENCY));

        // A latency breach on the same lane is a distinct episode: it must still alert even though the
        // drift latch is already set. A single per-lane latch would silently swallow it.
        for (int i = 0; i < 4; i++) {
            monitor.observe(
                    LANE,
                    true,
                    0.40,
                    Duration.ofMillis(2_500)); // 2,500 ms, above the 2-second self-hosted model budget
        }
        assertTrue(monitor.checkLatency(LANE, ProviderCategory.SELF_HOSTED, DecisionLatencyBudget.ADR_8_3));
        assertTrue(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.LATENCY));

        // And the cost-ceiling alert is a third, independent latch.
        monitor.alertCostCeiling(LANE, new BigDecimal("0.05"));
        assertTrue(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.COST_CEILING));

        // A per-lane reset clears every kind at once, so the lane starts a fresh episode.
        monitor.reset(LANE);
        assertFalse(monitor.alertEmitted(LANE));
        assertFalse(monitor.alertEmitted(LANE, DecisionDriftMonitor.AlertKind.DRIFT));
    }

    @Test
    void refusesAWindowThatCannotCompareTwoSamples() {
        assertThrows(IllegalArgumentException.class, () -> new DecisionDriftMonitor(1, 0.15, 0.20));
        assertThrows(IllegalArgumentException.class, () -> new DecisionDriftMonitor(4, -0.1, 0.20));
        assertThrows(IllegalArgumentException.class, () -> new DecisionDriftMonitor(4, 0.15, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new DecisionDriftMonitor(4, Double.NaN, 0.20));
        assertThrows(IllegalArgumentException.class, () -> new DecisionDriftMonitor(4, 0.15, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new DecisionDriftMonitor(4, 1.01, 0.20));
    }

    private static void observeLatency(DecisionDriftMonitor monitor, Duration latency, int count) {
        for (int i = 0; i < count; i++) {
            monitor.observe(LANE, true, 0.9, latency);
        }
    }
}
