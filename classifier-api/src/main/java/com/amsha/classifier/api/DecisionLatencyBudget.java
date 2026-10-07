package com.amsha.classifier.api;

import java.time.Duration;
import java.util.Objects;

/**
 * The per-lane p95 latency budgets the ADR sets for decision evaluation (M4).
 *
 * <p><b>Two lanes, not one.</b> The ADR's earlier single "&lt; 50 ms" figure was written for the local
 * intent pilot and is unreachable for a hosted provider, so it was superseded by a per-lane budget:
 * {@code < 50 ms} for an in-process or self-hosted local lane, and {@code < 500 ms} for a remote hosted
 * lane. A latency measured on one lane therefore says nothing about the other, and the two are never
 * combined into a single number — see {@link #forCategory(ProviderCategory)}.
 *
 * <p>This type carries only the <em>budget</em>. Computing the observed p95 from a set of measurements is
 * the evidence runner's job; keeping the two apart is what lets a budget change without touching the
 * maths, and lets the maths be tested against a budget that is not the shipped one.
 *
 * @param localP95 the p95 budget for an in-process or self-hosted lane
 * @param remoteP95 the p95 budget for a remote hosted lane
 */
public record DecisionLatencyBudget(Duration localP95, Duration remoteP95) {

    /**
     * The budgets as the ADR records them: 50 ms local, 500 ms remote.
     *
     * <p>It is a named constant rather than a literal at the call site so the two numbers move together
     * with the ADR and are not re-typed (and mistyped) per use.
     */
    public static final DecisionLatencyBudget ADR_8_3 =
            new DecisionLatencyBudget(Duration.ofMillis(50), Duration.ofMillis(500));

    public DecisionLatencyBudget {
        requirePositive(localP95, "localP95");
        requirePositive(remoteP95, "remoteP95");
    }

    /**
     * The budget for a provider category.
     *
     * @param category the provider category
     * @return the local budget for {@code IN_PROCESS}/{@code SELF_HOSTED}, the remote budget otherwise
     */
    public Duration forCategory(ProviderCategory category) {
        Objects.requireNonNull(category, "category");
        return category == ProviderCategory.REMOTE ? remoteP95 : localP95;
    }

    /**
     * Whether an observed p95 meets the budget for a provider category.
     *
     * <p>The comparison is strict: the ADR states the budget as {@code p95 < 50 ms}, so a p95 exactly on
     * the budget does not pass.
     *
     * @param category the provider category
     * @param observedP95 the measured p95 for that lane
     * @return true when the observed p95 is strictly below the lane's budget
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
        return "DecisionLatencyBudget[localP95=" + localP95.toMillis() + "ms, remoteP95=" + remoteP95.toMillis()
                + "ms]";
    }
}
