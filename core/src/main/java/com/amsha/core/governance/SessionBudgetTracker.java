package com.amsha.core.governance;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.MeterRegistry;

@ApplicationScoped
public class SessionBudgetTracker {

    @Inject
    MeterRegistry meterRegistry;

    public void recordContextRatio(double estimatedTokens, long maxTokensBeforeCompaction) {
        if (meterRegistry == null) return;
        double ratio = estimatedTokens / maxTokensBeforeCompaction;
        meterRegistry
                .summary("amsha.compaction.context.ratio", "tier", getRatioTier(ratio))
                .record(ratio);
    }

    public static String getRatioTier(double ratio) {
        if (ratio < 0.5) return "low";
        if (ratio < 0.9) return "medium";
        if (ratio < 1.1) return "high";
        return "critical";
    }
}
