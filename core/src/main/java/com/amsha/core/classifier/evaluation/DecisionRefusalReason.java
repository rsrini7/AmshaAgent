package com.amsha.core.classifier.evaluation;

/**
 * Why an evaluation was refused without egress.
 *
 * <p>It is a top-level type rather than an enum nested in the exception so that the service, the
 * exception, and the HTTP layer can all name a constant without a same-package nested-type import.
 * The set is closed: every refusal path resolves to exactly one of these, so the HTTP layer maps
 * status codes from this enum instead of parsing a message.
 */
public enum DecisionRefusalReason {

    /** The opt-in evaluation switch is off; no egress is permitted. */
    DISABLED,

    /** The call would have run on a Vert.x event-loop thread. */
    EVENT_LOOP,

    /** The request named a decision id that is not in the registry. */
    UNREGISTERED_DECISION,

    /** The request's structure is invalid for the named decision (for example its options). */
    INVALID_REQUEST,

    /** No usable provider is configured, or the configured one cannot be built. */
    PROVIDER_UNAVAILABLE
}
