package com.amsha.core.classifier.shadow;

import java.util.Map;

/**
 * Aggregated evaluation metrics for a candidate evaluated across the shadow corpus.
 */
public record CandidateMetrics(
        String candidateName,
        int totalCases,
        int abstentions,
        int evaluatedCases,
        int correctCases,
        double accuracyPct,
        int falseAllows,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        int timeouts,
        double estimatedCostUsd,
        double meanConfidence,
        double calibrationGap,
        Map<String, PartitionScore> partitionScores) {

    public CandidateMetrics {
        partitionScores = partitionScores != null ? Map.copyOf(partitionScores) : Map.of();
    }

    public record PartitionScore(int total, int correct, int abstentions, int falseAllows) {}
}
