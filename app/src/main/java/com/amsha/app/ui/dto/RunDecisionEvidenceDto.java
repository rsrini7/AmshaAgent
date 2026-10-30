package com.amsha.app.ui.dto;

import java.time.Instant;
import java.util.Objects;

/** Safe, payload-free decision evidence from one persisted run trace event. */
public record RunDecisionEvidenceDto(String decision, String riskLevel, Instant timestamp) {
    public RunDecisionEvidenceDto {
        Objects.requireNonNull(decision, "decision is required");
        Objects.requireNonNull(timestamp, "timestamp is required");
        if (!RunEvidenceContract.DECISIONS.contains(decision)) {
            throw new IllegalArgumentException("decision is not allowlisted");
        }
        if (riskLevel != null && !RunEvidenceContract.isRiskLevel(riskLevel)) {
            throw new IllegalArgumentException("riskLevel is not allowlisted");
        }
    }
}
