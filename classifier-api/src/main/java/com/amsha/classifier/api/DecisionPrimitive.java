package com.amsha.classifier.api;

/**
 * Bounded judgment primitives for the decision plane.
 * Semantic distinctions are non-interchangeable:
 * <ul>
 *   <li>{@link #CHOICE}: Select one item from an admissible closed set.</li>
 *   <li>{@link #SCORE}: Place a subject on an ordered rubric.</li>
 *   <li>{@link #NOUL}: Probability that an atomic proposition is true.</li>
 * </ul>
 */
public enum DecisionPrimitive {
    CHOICE,
    SCORE,
    NOUL
}
