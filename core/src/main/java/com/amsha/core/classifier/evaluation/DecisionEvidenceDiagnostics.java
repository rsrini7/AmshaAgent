package com.amsha.core.classifier.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Aggregate, payload-free operational diagnostics for one decision-evidence run. */
public record DecisionEvidenceDiagnostics(
        int evaluatedCaseCount,
        int refusedCaseCount,
        int transformErrorCaseCount,
        int skippedCaseCount,
        List<ProviderUsage> providerUsage) {

    public DecisionEvidenceDiagnostics {
        if (evaluatedCaseCount < 0 || refusedCaseCount < 0 || transformErrorCaseCount < 0 || skippedCaseCount < 0) {
            throw new IllegalArgumentException("diagnostic case counts must not be negative");
        }
        providerUsage = List.copyOf(Objects.requireNonNull(providerUsage, "providerUsage"));
    }

    static DecisionEvidenceDiagnostics from(List<DecisionEvidenceCaseResult> results) {
        int evaluated = 0;
        int refused = 0;
        int transformErrors = 0;
        int skipped = 0;
        Map<ProviderKey, UsageAccumulator> usage = new LinkedHashMap<>();

        for (DecisionEvidenceCaseResult result : results) {
            if (result.evaluated()) {
                evaluated++;
                ProviderKey key = new ProviderKey(result.providerId(), result.model(), result.lane());
                usage.computeIfAbsent(key, _ -> new UsageAccumulator()).add(result);
            } else if (result.refused()) {
                refused++;
            } else if (result.transformError()) {
                transformErrors++;
            } else if (result.skippedNoDecision()) {
                skipped++;
            }
        }

        return new DecisionEvidenceDiagnostics(
                evaluated,
                refused,
                transformErrors,
                skipped,
                usage.entrySet().stream()
                        .map(entry -> entry.getValue().toProviderUsage(entry.getKey()))
                        .toList());
    }

    /** Aggregated telemetry for one provider/model/lane tuple; all identity fields may be absent. */
    public record ProviderUsage(
            String providerId,
            String model,
            String lane,
            int evaluatedCaseCount,
            int inputTokenReportedCaseCount,
            int outputTokenReportedCaseCount,
            long totalInputTokens,
            long totalOutputTokens) {

        public ProviderUsage {
            if (evaluatedCaseCount < 0
                    || inputTokenReportedCaseCount < 0
                    || outputTokenReportedCaseCount < 0
                    || totalInputTokens < 0
                    || totalOutputTokens < 0) {
                throw new IllegalArgumentException("provider usage counts and token totals must not be negative");
            }
            if (inputTokenReportedCaseCount > evaluatedCaseCount || outputTokenReportedCaseCount > evaluatedCaseCount) {
                throw new IllegalArgumentException("token report counts must not exceed evaluated cases");
            }
            if ((inputTokenReportedCaseCount == 0 && totalInputTokens != 0)
                    || (outputTokenReportedCaseCount == 0 && totalOutputTokens != 0)) {
                throw new IllegalArgumentException("token totals must be zero when no token reports were received");
            }
        }
    }

    private record ProviderKey(String providerId, String model, String lane) {}

    private static final class UsageAccumulator {
        private int evaluatedCaseCount;
        private int inputTokenReportedCaseCount;
        private int outputTokenReportedCaseCount;
        private long totalInputTokens;
        private long totalOutputTokens;

        private void add(DecisionEvidenceCaseResult result) {
            evaluatedCaseCount++;
            if (result.inputTokens() != null) {
                inputTokenReportedCaseCount++;
                totalInputTokens = Math.addExact(totalInputTokens, result.inputTokens());
            }
            if (result.outputTokens() != null) {
                outputTokenReportedCaseCount++;
                totalOutputTokens = Math.addExact(totalOutputTokens, result.outputTokens());
            }
        }

        private ProviderUsage toProviderUsage(ProviderKey key) {
            return new ProviderUsage(
                    key.providerId(),
                    key.model(),
                    key.lane(),
                    evaluatedCaseCount,
                    inputTokenReportedCaseCount,
                    outputTokenReportedCaseCount,
                    totalInputTokens,
                    totalOutputTokens);
        }
    }
}
