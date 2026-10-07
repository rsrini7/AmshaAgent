package com.amsha.core.classifier.evaluation;

import java.util.Objects;

/**
 * The single fail-closed refusal of the evaluation path.
 *
 * <p>Every reason an evaluation can be refused without egress resolves to one of the
 * {@link DecisionRefusalReason} constants, so the HTTP layer maps status codes from a closed set
 * instead of parsing a message. The message names operator configuration or the request's structural
 * fault only — it never carries state, a credential, a credential reference value, or a provider body.
 *
 * <p>It is a {@link RuntimeException} for the same reason
 * {@link com.amsha.core.classifier.provider.DecisionProviderUnavailableException} is: every caller
 * either maps it to a refusal response or is a test, and a checked exception would force swallow-and-
 * ignore {@code catch} blocks.
 */
public class DecisionEvaluationRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final DecisionRefusalReason reason;

    public DecisionEvaluationRefusedException(DecisionRefusalReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public DecisionEvaluationRefusedException(DecisionRefusalReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** The categorical refusal reason. */
    public DecisionRefusalReason reason() {
        return reason;
    }
}
