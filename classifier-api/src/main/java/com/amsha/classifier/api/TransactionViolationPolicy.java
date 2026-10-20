package com.amsha.classifier.api;

/**
 * Defensive policy applied when an active JTA write transaction is detected (§5.9).
 */
public enum TransactionViolationPolicy {
    /**
     * Fails fast by immediately throwing an {@link IllegalStateException}.
     */
    FAIL_FAST_EXCEPTION,

    /**
     * Fails closed by returning a {@link DecisionResult.NoOpinion} with
     * {@link ClassifierResult.NoOpinionReason#OVERLOAD_REJECTED}.
     */
    FAIL_CLOSED_NO_OPINION
}
