package com.amsha.classifier.api;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Deterministic, payload-free policy resolution for intent decisions.
 *
 * <p>The compact constructor enforces the cross-field rules below: a
 * {@link IntentDecisionAction#DIRECT_ROUTE} must carry a selected role, and {@code fallback} must agree
 * with the action ({@code true} only for {@link IntentDecisionAction#DETERMINISTIC_FALLBACK}).
 *
 * @param action the resolved routing action
 * @param selectedRole the selected role (present on DIRECT_ROUTE or SECONDARY_REVIEW if selected)
 * @param confidence provider confidence if reported
 * @param decisionId decision identifier
 * @param schemaVersion decision schema version
 * @param reason deterministic reason token
 * @param fallback true if this outcome was produced via deterministic fallback
 */
public record IntentPolicyResolution(
        IntentDecisionAction action,
        Optional<String> selectedRole,
        OptionalDouble confidence,
        String decisionId,
        int schemaVersion,
        String reason,
        boolean fallback) {

    public IntentPolicyResolution {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(selectedRole, "selectedRole");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(reason, "reason");
        // Cross-field rules this record's contract promises, enforced here so a consumer can branch on
        // either the action or the fallback flag without the two ever disagreeing.
        if (action == IntentDecisionAction.DIRECT_ROUTE && selectedRole.isEmpty()) {
            throw new IllegalArgumentException("DIRECT_ROUTE requires a selected role");
        }
        boolean fallbackAction = action == IntentDecisionAction.DETERMINISTIC_FALLBACK;
        if (fallback != fallbackAction) {
            throw new IllegalArgumentException(
                    "fallback must be " + fallbackAction + " for action " + action + ", got " + fallback);
        }
    }

    public static IntentPolicyResolution direct(
            String role, double confidence, String decisionId, int schemaVersion, String reason) {
        return new IntentPolicyResolution(
                IntentDecisionAction.DIRECT_ROUTE,
                Optional.of(role),
                OptionalDouble.of(confidence),
                decisionId,
                schemaVersion,
                reason,
                false);
    }

    public static IntentPolicyResolution secondaryReview(
            Optional<String> role, OptionalDouble confidence, String decisionId, int schemaVersion, String reason) {
        return new IntentPolicyResolution(
                IntentDecisionAction.SECONDARY_REVIEW, role, confidence, decisionId, schemaVersion, reason, false);
    }

    public static IntentPolicyResolution fallback(
            String decisionId, int schemaVersion, OptionalDouble confidence, String reason) {
        return new IntentPolicyResolution(
                IntentDecisionAction.DETERMINISTIC_FALLBACK,
                Optional.empty(),
                confidence,
                decisionId,
                schemaVersion,
                reason,
                true);
    }
}
