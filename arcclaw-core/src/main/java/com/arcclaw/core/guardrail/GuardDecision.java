package com.arcclaw.core.guardrail;

public sealed interface GuardDecision permits GuardDecision.Allow, GuardDecision.Block, GuardDecision.Retry, GuardDecision.Sanitize {

    record Allow() implements GuardDecision {}
    record Block(String reason, String detail) implements GuardDecision {}
    record Retry(String reason, String correctedInput) implements GuardDecision {}
    record Sanitize(String sanitizedInput) implements GuardDecision {}

    default boolean isAllowed() { return this instanceof Allow; }
    default boolean isBlocked() { return this instanceof Block; }
    default boolean isRetryable() { return this instanceof Retry; }
    default boolean isSanitize() { return this instanceof Sanitize; }
}
