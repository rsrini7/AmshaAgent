package com.amsha.classifier.api;

/**
 * Action emitted by deterministic intent routing policy evaluation (§2, §7.7).
 */
public enum IntentDecisionAction {
    /** Route directly to the selected specialist role with high confidence. */
    DIRECT_ROUTE,
    /** Middle-band confidence: escalate to stronger model or secondary review. */
    SECONDARY_REVIEW,
    /** Low confidence or abstention: fall back to deterministic baseline routing. */
    DETERMINISTIC_FALLBACK
}
