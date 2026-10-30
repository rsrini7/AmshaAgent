package com.amsha.app.ui.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Bounded, payload-free operator evidence correlated to one durable run id. */
public record RunEvidenceDto(
        String runId,
        String outcomeStatus,
        String outcomeAvailability,
        Long latencyMs,
        String latencyAvailability,
        long observedEventCount,
        boolean eventsTruncated,
        List<RunDecisionEvidenceDto> guardDecisions,
        List<RunDecisionEvidenceDto> approvalDecisions,
        boolean decisionEvidenceTruncated,
        Long promptTokens,
        Long completionTokens,
        String usageAvailability,
        String costStatus,
        BigDecimal estimatedCost,
        String costCurrency,
        String costReason) {

    public RunEvidenceDto {
        Objects.requireNonNull(runId, "runId is required");
        Objects.requireNonNull(outcomeStatus, "outcomeStatus is required");
        Objects.requireNonNull(outcomeAvailability, "outcomeAvailability is required");
        Objects.requireNonNull(latencyAvailability, "latencyAvailability is required");
        Objects.requireNonNull(usageAvailability, "usageAvailability is required");
        Objects.requireNonNull(costStatus, "costStatus is required");
        Objects.requireNonNull(costReason, "costReason is required");
        guardDecisions = List.copyOf(Objects.requireNonNull(guardDecisions, "guardDecisions are required"));
        approvalDecisions = List.copyOf(Objects.requireNonNull(approvalDecisions, "approvalDecisions are required"));
        if (observedEventCount < 0) {
            throw new IllegalArgumentException("observedEventCount must not be negative");
        }
        if (guardDecisions.size() > RunEvidenceContract.MAX_DECISIONS
                || approvalDecisions.size() > RunEvidenceContract.MAX_DECISIONS) {
            throw new IllegalArgumentException("decision evidence exceeds its response limit");
        }
        if (eventsTruncated && !decisionEvidenceTruncated) {
            throw new IllegalArgumentException("truncated events require truncated decision evidence state");
        }
        if (latencyMs != null && latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
        if ((promptTokens != null && promptTokens < 0) || (completionTokens != null && completionTokens < 0)) {
            throw new IllegalArgumentException("token measurements must not be negative");
        }
        if (!RunEvidenceContract.OUTCOME_AVAILABILITY.contains(outcomeAvailability)
                || !RunEvidenceContract.OUTCOME_STATUS.contains(outcomeStatus)
                || (RunEvidenceContract.UNAVAILABLE.equals(outcomeAvailability)
                        != RunEvidenceContract.UNAVAILABLE.equals(outcomeStatus))) {
            throw new IllegalArgumentException("outcome availability does not match its status");
        }
        if (!RunEvidenceContract.LATENCY_AVAILABILITY.contains(latencyAvailability)
                || (RunEvidenceContract.MEASURED.equals(latencyAvailability) != (latencyMs != null))) {
            throw new IllegalArgumentException("latency availability does not match its measurement");
        }
        if (!RunEvidenceContract.USAGE_AVAILABILITY.contains(usageAvailability)
                || !RunEvidenceContract.COST_STATUS.contains(costStatus)) {
            throw new IllegalArgumentException("usage or cost state is invalid");
        }
        if (RunEvidenceContract.UNAVAILABLE.equals(usageAvailability)
                && (promptTokens != null || completionTokens != null)) {
            throw new IllegalArgumentException("unavailable usage cannot include token measurements");
        }
        boolean usageObserved = !RunEvidenceContract.UNAVAILABLE.equals(usageAvailability);
        if (usageObserved == RunEvidenceContract.UNAVAILABLE.equals(costStatus)) {
            throw new IllegalArgumentException("cost availability must match run-scoped usage evidence");
        }
        if (estimatedCost != null && (estimatedCost.signum() < 0 || costCurrency == null || costCurrency.isBlank())) {
            throw new IllegalArgumentException("priced cost requires a non-negative amount and currency");
        }
        if (RunEvidenceContract.PRICED.equals(costStatus) != (estimatedCost != null)) {
            throw new IllegalArgumentException("priced cost state must match its amount");
        }
        if (estimatedCost == null && costCurrency != null) {
            throw new IllegalArgumentException("costCurrency requires an estimated cost");
        }
    }
}
