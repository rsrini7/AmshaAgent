package com.amsha.classifier.api;

/**
 * Sealed base interface for typed answers to bounded decision questions.
 * Permitted implementations are {@link Choice}, {@link Score}, and {@link Noul}.
 * Values of different primitives must not be compared or converted implicitly.
 */
public sealed interface DecisionAnswer permits Choice, Score, Noul {

    /**
     * Returns the primitive category corresponding to this answer.
     *
     * @return the decision primitive
     */
    DecisionPrimitive primitive();
}
