package com.amsha.classifier.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Registered configuration contract defining a bounded decision site.
 * Holds schema version, primitive rubric, state projection, criteria,
 * candidate-reduction policy, provider category boundaries, and fail mode.
 *
 * @param decisionId unique stable decision identifier
 * @param schemaVersion positive schema version integer
 * @param primitive target decision primitive
 * @param criteria evaluation criteria and instructions
 * @param stateProjection definition of required state fields
 * @param redactionPolicy redaction and masking rules
 * @param retentionPolicy data retention and residency constraints
 * @param candidateReductionPolicy shortlist reduction rules for closed sets
 * @param reviewBand confidence threshold bands for policy action
 * @param allowedProviders permitted provider deployment categories
 * @param failMode failure handling mode on provider error or abstention
 * @param timeout per-evaluation execution deadline
 * @param rollbackEnabled whether the decision can be rolled back to fallback baseline
 */
public record DecisionDefinition(
        String decisionId,
        int schemaVersion,
        DecisionPrimitive primitive,
        String criteria,
        String stateProjection,
        RedactionPolicy redactionPolicy,
        RetentionPolicy retentionPolicy,
        CandidateReductionPolicy candidateReductionPolicy,
        ReviewBand reviewBand,
        Set<ProviderCategory> allowedProviders,
        FailMode failMode,
        Duration timeout,
        boolean rollbackEnabled) {

    public DecisionDefinition {
        Objects.requireNonNull(decisionId, "decisionId");
        if (decisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId must not be blank");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1, got " + schemaVersion);
        }
        Objects.requireNonNull(primitive, "primitive");
        Objects.requireNonNull(criteria, "criteria");
        if (criteria.isBlank()) {
            throw new IllegalArgumentException("criteria must not be blank");
        }
        Objects.requireNonNull(stateProjection, "stateProjection");
        Objects.requireNonNull(redactionPolicy, "redactionPolicy");
        Objects.requireNonNull(retentionPolicy, "retentionPolicy");
        Objects.requireNonNull(candidateReductionPolicy, "candidateReductionPolicy");
        Objects.requireNonNull(reviewBand, "reviewBand");
        Objects.requireNonNull(allowedProviders, "allowedProviders");
        if (allowedProviders.isEmpty()) {
            throw new IllegalArgumentException("allowedProviders must not be empty");
        }
        allowedProviders = Set.copyOf(allowedProviders);
        Objects.requireNonNull(failMode, "failMode");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
    }
}
