package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Contract for the per-lane p95 latency budgets (M4).
 *
 * <p>The load-bearing property is that the two lanes are distinct: a measurement that passes the remote
 * budget must not be judged against the local one, and vice versa. The boundary cases assert the ADR's
 * strict {@code <} rather than {@code <=}.
 */
class DecisionLatencyBudgetTest {

    @Test
    void theAdrBudgetsAreFiftyMillisecondsLocalAndFiveHundredRemote() {
        assertEquals(Duration.ofMillis(50), DecisionLatencyBudget.ADR_8_3.localP95());
        assertEquals(Duration.ofMillis(500), DecisionLatencyBudget.ADR_8_3.remoteP95());
    }

    @Test
    void inProcessAndSelfHostedShareTheLocalBudgetAndRemoteDoesNot() {
        DecisionLatencyBudget budget = DecisionLatencyBudget.ADR_8_3;

        assertEquals(Duration.ofMillis(50), budget.forCategory(ProviderCategory.IN_PROCESS));
        assertEquals(Duration.ofMillis(50), budget.forCategory(ProviderCategory.SELF_HOSTED));
        assertEquals(Duration.ofMillis(500), budget.forCategory(ProviderCategory.REMOTE));
    }

    @Test
    void theComparisonIsStrictlyBelowTheBudget() {
        DecisionLatencyBudget budget = DecisionLatencyBudget.ADR_8_3;

        assertTrue(budget.isWithin(ProviderCategory.SELF_HOSTED, Duration.ofMillis(49)));
        assertFalse(budget.isWithin(ProviderCategory.SELF_HOSTED, Duration.ofMillis(50)), "exactly on budget fails");
        assertTrue(budget.isWithin(ProviderCategory.REMOTE, Duration.ofMillis(499)));
        assertFalse(budget.isWithin(ProviderCategory.REMOTE, Duration.ofMillis(500)), "exactly on budget fails");
    }

    @Test
    void aRemoteLaneIsNotJudgedAgainstTheLocalBudget() {
        DecisionLatencyBudget budget = DecisionLatencyBudget.ADR_8_3;

        // 300 ms is over the local budget but well within the remote one; conflating the lanes would fail it.
        assertFalse(budget.isWithin(ProviderCategory.SELF_HOSTED, Duration.ofMillis(300)));
        assertTrue(budget.isWithin(ProviderCategory.REMOTE, Duration.ofMillis(300)));
    }

    @Test
    void refusesANonPositiveBudget() {
        assertThrows(
                IllegalArgumentException.class, () -> new DecisionLatencyBudget(Duration.ZERO, Duration.ofMillis(500)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionLatencyBudget(Duration.ofMillis(50), Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> new DecisionLatencyBudget(null, Duration.ofMillis(500)));
    }

    @Test
    void aToStringIsBoundedAndNamesBothLanes() {
        String rendered = DecisionLatencyBudget.ADR_8_3.toString();

        assertTrue(rendered.contains("50"), rendered);
        assertTrue(rendered.contains("500"), rendered);
    }
}
