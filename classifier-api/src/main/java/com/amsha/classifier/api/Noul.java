package com.amsha.classifier.api;

/**
 * Answer to a noul decision question: the probability that an atomic proposition is true.
 * A noul represents proposition probability, not degree, severity, or completion.
 *
 * @param probability probability that the proposition is affirmed, in [0.0, 1.0]
 */
public record Noul(double probability) implements DecisionAnswer {

    public Noul {
        if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException(
                    "Noul proposition probability must be finite and in [0.0, 1.0], got " + probability);
        }
    }

    @Override
    public DecisionPrimitive primitive() {
        return DecisionPrimitive.NOUL;
    }

    /**
     * Determines whether the proposition is affirmed against an application policy threshold.
     *
     * @param threshold minimum probability threshold in [0.0, 1.0]
     * @return true if probability is at or above the threshold
     */
    public boolean isAffirmed(double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be finite and in [0.0, 1.0], got " + threshold);
        }
        return probability >= threshold;
    }
}
