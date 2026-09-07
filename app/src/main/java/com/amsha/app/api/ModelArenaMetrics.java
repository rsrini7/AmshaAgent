package com.amsha.app.api;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;

final class ModelArenaMetrics {

    static final String COMPARISON_TOTAL = "amsha.arena.comparisons.total";
    static final String COMPARISON_DURATION = "amsha.arena.comparison.duration";
    static final String TOKENS_TOTAL = "amsha.arena.tokens.total";
    static final String COST_TOTAL = "amsha.arena.cost.usd";

    private ModelArenaMetrics() {}

    static void recordComparison(
            MeterRegistry registry,
            String status,
            int modelCount,
            long durationNanos,
            int inputTokens,
            int outputTokens,
            double costUsd) {
        if (registry == null) {
            return;
        }
        try {
            String safeStatus = resolveStatus(status);
            // Tag cardinality is strictly bounded to [0, 3] matching ModelArenaHelper.MAX_MODELS
            String countTag = String.valueOf(Math.min(3, Math.max(0, modelCount)));

            registry.counter(COMPARISON_TOTAL, "status", safeStatus, "models", countTag)
                    .increment();
            registry.timer(COMPARISON_DURATION, "status", safeStatus).record(durationNanos, TimeUnit.NANOSECONDS);

            if (inputTokens > 0) {
                registry.counter(TOKENS_TOTAL, "type", "input").increment(inputTokens);
            }
            if (outputTokens > 0) {
                registry.counter(TOKENS_TOTAL, "type", "output").increment(outputTokens);
            }
            if (costUsd > 0.0) {
                registry.counter(COST_TOTAL).increment(costUsd);
            }
        } catch (Exception _) {
            // Metrics must never fail arena operations
        }
    }

    static String resolveStatus(String status) {
        if (status == null || status.isBlank()) {
            return "unknown";
        }
        return status.trim().toLowerCase(Locale.ROOT);
    }
}
