package com.amsha.classifier.api;

/**
 * Public provider-neutral decision engine seam for typed agent judgments.
 * Concentrates provider transport, decoding, redaction, and failure mapping behind
 * a small, deep seam: {@code evaluate(DecisionRequest)}.
 *
 * <p>Execution must occur outside active database transactions (never inside
 * {@code @Transactional} persistence writes).
 */
@FunctionalInterface
public interface DecisionEngine {

    /**
     * Evaluates the decision request against its registered questions and bounded state.
     *
     * @param request the validated decision request
     * @return a sealed {@link DecisionResult} (either {@link DecisionResult.Opinion} or {@link DecisionResult.NoOpinion})
     */
    DecisionResult evaluate(DecisionRequest request);

    /**
     * Declares the provider deployment category of this engine.
     *
     * @return provider deployment category
     */
    default ProviderCategory category() {
        return ProviderCategory.IN_PROCESS;
    }
}
