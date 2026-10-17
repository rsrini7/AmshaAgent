package com.amsha.classifier.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-category p95 latency budgets for decision evaluation (ADR §8.3 M4).
 *
 * <p>The original {@code < 50 ms} target remains for in-process classifiers. Self-hosted local
 * models have a separate {@code < 2,000 ms} p95 budget, with 1–2 seconds as the expected operating
 * range. Remote hosted models keep their {@code < 750 ms} p95 budget. Measurements are evaluated
 * per category and never combined.
 *
 * <p>This type carries only budgets. The evidence runner computes observed p95 from measurements,
 * keeping the budget independently testable against the measured result.
 *
 * @param inProcessP95 p95 budget for an in-process classifier
 * @param selfHostedP95 p95 budget for a self-hosted model
 * @param remoteP95 p95 budget for a remote hosted model
 */
public record DecisionLatencyBudget(Duration inProcessP95, Duration selfHostedP95, Duration remoteP95) {

    /** ADR §8.3 budgets: 50 ms in-process, 2,000 ms self-hosted, and 750 ms remote. */
    public static final DecisionLatencyBudget ADR_8_3 =
            new DecisionLatencyBudget(Duration.ofMillis(50), Duration.ofMillis(2_000), Duration.ofMillis(750));

    public DecisionLatencyBudget {
        requirePositive(inProcessP95, "inProcessP95");
        requirePositive(selfHostedP95, "selfHostedP95");
        requirePositive(remoteP95, "remoteP95");
    }

    /** Returns the p95 budget for a provider category. */
    public Duration forCategory(ProviderCategory category) {
        Objects.requireNonNull(category, "category");
        return switch (category) {
            case IN_PROCESS -> inProcessP95;
            case SELF_HOSTED -> selfHostedP95;
            case REMOTE -> remoteP95;
        };
    }

    /**
     * Returns whether an observed p95 is strictly below its category budget.
     *
     * @param category provider category
     * @param observedP95 measured p95 for that category
     * @return true when the observed p95 is below the category budget
     */
    public boolean isWithin(ProviderCategory category, Duration observedP95) {
        Objects.requireNonNull(observedP95, "observedP95");
        return observedP95.compareTo(forCategory(category)) < 0;
    }

    private static void requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive, got " + value);
        }
    }

    @Override
    public String toString() {
        return "DecisionLatencyBudget[inProcessP95="
                + inProcessP95.toMillis()
                + "ms, selfHostedP95="
                + selfHostedP95.toMillis()
                + "ms, remoteP95="
                + remoteP95.toMillis()
                + "ms]";
    }
}
