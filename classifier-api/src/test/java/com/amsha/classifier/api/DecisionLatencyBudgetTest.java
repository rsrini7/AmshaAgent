package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Contract for the per-category p95 latency budgets in ADR §8.3 M4. */
class DecisionLatencyBudgetTest {

    @Test
    void theAdrBudgetsSeparateInProcessSelfHostedAndRemoteModels() {
        DecisionLatencyBudget budget = DecisionLatencyBudget.ADR_8_3;

        assertEquals(Duration.ofMillis(50), budget.inProcessP95());
        assertEquals(Duration.ofMillis(2_000), budget.selfHostedP95());
        assertEquals(Duration.ofMillis(750), budget.remoteP95());
    }

    @Test
    void eachProviderCategoryUsesItsOwnBudget() {
        DecisionLatencyBudget budget = DecisionLatencyBudget.ADR_8_3;

        assertEquals(Duration.ofMillis(50), budget.forCategory(ProviderCategory.IN_PROCESS));
        assertEquals(Duration.ofMillis(2_000), budget.forCategory(ProviderCategory.SELF_HOSTED));
        assertEquals(Duration.ofMillis(750), budget.forCategory(ProviderCategory.REMOTE));
    }

    @Test
    void theComparisonIsStrictlyBelowEachCategoryBudget() {
        DecisionLatencyBudget budget = DecisionLatencyBudget.ADR_8_3;

        assertTrue(budget.isWithin(ProviderCategory.IN_PROCESS, Duration.ofMillis(49)));
        assertFalse(budget.isWithin(ProviderCategory.IN_PROCESS, Duration.ofMillis(50)), "exactly on budget fails");
        assertTrue(budget.isWithin(ProviderCategory.SELF_HOSTED, Duration.ofMillis(1_999)));
        assertFalse(budget.isWithin(ProviderCategory.SELF_HOSTED, Duration.ofMillis(2_000)), "exactly on budget fails");
        assertTrue(budget.isWithin(ProviderCategory.REMOTE, Duration.ofMillis(749)));
        assertFalse(budget.isWithin(ProviderCategory.REMOTE, Duration.ofMillis(750)), "exactly on budget fails");
    }

    @Test
    void aOnePointFiveSecondLocalModelP95MeetsTheBudget() {
        assertTrue(DecisionLatencyBudget.ADR_8_3.isWithin(ProviderCategory.SELF_HOSTED, Duration.ofMillis(1_500)));
    }

    @Test
    void rejectsNonPositiveBudgets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionLatencyBudget(Duration.ZERO, Duration.ofMillis(2_000), Duration.ofMillis(750)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionLatencyBudget(Duration.ofMillis(50), Duration.ofMillis(-1), Duration.ofMillis(750)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionLatencyBudget(Duration.ofMillis(50), Duration.ofMillis(2_000), Duration.ZERO));
        assertThrows(
                NullPointerException.class,
                () -> new DecisionLatencyBudget(null, Duration.ofMillis(2_000), Duration.ofMillis(750)));
    }

    @Test
    void toStringIncludesAllThreeBudgets() {
        String rendered = DecisionLatencyBudget.ADR_8_3.toString();

        assertTrue(rendered.contains("50"));
        assertTrue(rendered.contains("2000"));
        assertTrue(rendered.contains("750"));
    }
}
