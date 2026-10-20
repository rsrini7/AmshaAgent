package com.amsha.classifier.api;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Deterministic policy module for intent decision evaluation.
 *
 * <p>Enforces:
 * <ul>
 *   <li><b>Review bands:</b> Uses {@link ReviewBand} thresholds to route high confidence
 *       (&ge; highThreshold) to {@link IntentDecisionAction#DIRECT_ROUTE}, middle confidence
 *       ([lowThreshold, highThreshold)) to {@link IntentDecisionAction#SECONDARY_REVIEW},
 *       and low confidence or abstention to {@link IntentDecisionAction#DETERMINISTIC_FALLBACK}.</li>
 *   <li><b>Closed option set:</b> Enforces declared admissible role shortlist; unrecognized role
 *       labels fall back deterministically with reason {@code UNRECOGNIZED_ROLE_LABEL}.</li>
 *   <li><b>Absence preservation:</b> Clean {@link DecisionResult.NoOpinion} preserves its reason
 *       without fabricating confidence or certainty.</li>
 *   <li><b>Payload-free diagnostics:</b> Never emits user message or provider prompt payloads in
 *       reasons or metadata.</li>
 * </ul>
 */
public final class IntentDecisionPolicy {

    /** Reserved choice label that means the model declines to select a specialist. */
    public static final String NO_OPINION_LABEL = "NO_OPINION";

    public static final ReviewBand DEFAULT_INTENT_REVIEW_BAND = new ReviewBand(0.50, 0.85);

    private IntentDecisionPolicy() {}

    /**
     * Resolves an intent decision result into an {@link IntentPolicyResolution} using identity verification,
     * review band thresholds, and declared role shortlist validation.
     *
     * @param result raw decision engine result (may be null or NoOpinion)
     * @param expectedDecisionId required expected decision identifier
     * @param expectedSchemaVersion required expected schema version
     * @param targetKey target choice question key (or null to inspect the first answer)
     * @param validRoles declared admissible role labels (or empty set if unrestricted)
     * @param reviewBand review band thresholds (defaults to {@link #DEFAULT_INTENT_REVIEW_BAND} if null)
     * @return deterministic, payload-free policy resolution
     */
    public static IntentPolicyResolution resolve(
            DecisionResult result,
            String expectedDecisionId,
            int expectedSchemaVersion,
            QuestionKey<Choice> targetKey,
            Set<String> validRoles,
            ReviewBand reviewBand) {
        if (expectedDecisionId == null || expectedDecisionId.isBlank()) {
            throw new IllegalArgumentException("expectedDecisionId must not be null or blank");
        }
        if (expectedSchemaVersion < 1) {
            throw new IllegalArgumentException("expectedSchemaVersion must be >= 1");
        }

        ReviewBand band = reviewBand != null ? reviewBand : DEFAULT_INTENT_REVIEW_BAND;
        Set<String> roles = validRoles != null ? validRoles : Set.of();

        if (result == null) {
            return IntentPolicyResolution.fallback(
                    expectedDecisionId, expectedSchemaVersion, OptionalDouble.empty(), "INFERENCE_ERROR");
        }

        // §Result identity verification: Reject decisionId and schemaVersion mismatches to deterministic fallback
        if (!expectedDecisionId.equals(result.decisionId())) {
            return IntentPolicyResolution.fallback(
                    expectedDecisionId, expectedSchemaVersion, OptionalDouble.empty(), "DECISION_ID_MISMATCH");
        }
        if (result.schemaVersion() != expectedSchemaVersion) {
            return IntentPolicyResolution.fallback(
                    expectedDecisionId, expectedSchemaVersion, OptionalDouble.empty(), "SCHEMA_VERSION_MISMATCH");
        }

        if (result instanceof DecisionResult.NoOpinion noOp) {
            return IntentPolicyResolution.fallback(
                    noOp.decisionId(),
                    noOp.schemaVersion(),
                    OptionalDouble.empty(),
                    noOp.reason().name());
        }

        DecisionResult.Opinion opinion = (DecisionResult.Opinion) result;
        DecisionAnswer answer = null;
        if (targetKey != null) {
            try {
                answer = opinion.get(targetKey);
            } catch (IllegalArgumentException _) {
                return IntentPolicyResolution.fallback(
                        opinion.decisionId(),
                        opinion.schemaVersion(),
                        OptionalDouble.empty(),
                        "UNKNOWN_QUESTION_HANDLE");
            }
        } else if (!opinion.answers().isEmpty()) {
            answer = opinion.answers().values().iterator().next();
        }

        if (!(answer instanceof Choice choice)) {
            return IntentPolicyResolution.fallback(
                    opinion.decisionId(), opinion.schemaVersion(), OptionalDouble.empty(), "UNMAPPED_PRIMITIVE");
        }

        String label = choice.label();
        if (NO_OPINION_LABEL.equals(label)) {
            return IntentPolicyResolution.fallback(
                    opinion.decisionId(), opinion.schemaVersion(), choice.confidence(), "MODEL_ABSTAINED");
        }
        if (!roles.isEmpty() && !roles.contains(label)) {
            return IntentPolicyResolution.fallback(
                    opinion.decisionId(), opinion.schemaVersion(), choice.confidence(), "UNRECOGNIZED_ROLE_LABEL");
        }

        OptionalDouble confOpt = choice.confidence();
        if (confOpt.isEmpty()) {
            return IntentPolicyResolution.fallback(
                    opinion.decisionId(), opinion.schemaVersion(), OptionalDouble.empty(), "MISSING_CONFIDENCE");
        }

        double conf = confOpt.getAsDouble();
        if (band.isHigh(conf)) {
            return IntentPolicyResolution.direct(
                    label, conf, opinion.decisionId(), opinion.schemaVersion(), "HIGH_CONFIDENCE_ROUTE");
        }
        if (band.isReview(conf)) {
            return IntentPolicyResolution.secondaryReview(
                    Optional.of(label), confOpt, opinion.decisionId(), opinion.schemaVersion(), "MIDDLE_REVIEW_BAND");
        }
        return IntentPolicyResolution.fallback(
                opinion.decisionId(), opinion.schemaVersion(), confOpt, "LOW_CONFIDENCE");
    }
}
