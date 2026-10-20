package com.amsha.classifier.api;

/**
 * Three-band confidence threshold configuration for deterministic policy handling:
 * <ul>
 *   <li>High confidence (at or above {@code highThreshold}): apply permitted policy branch.</li>
 *   <li>Review band (between {@code lowThreshold} and {@code highThreshold}): route to human review,
 *       clarification, or stronger model.</li>
 *   <li>Low confidence (below {@code lowThreshold}): deterministic fallback or NoOpinion.</li>
 * </ul>
 *
 * @param lowThreshold floor below which judgment falls back to default/NoOpinion
 * @param highThreshold ceiling above which judgment is accepted with high confidence
 */
public record ReviewBand(double lowThreshold, double highThreshold) {

    public ReviewBand {
        if (!Double.isFinite(lowThreshold) || lowThreshold < 0.0 || lowThreshold > 1.0) {
            throw new IllegalArgumentException("lowThreshold must be finite and in [0.0, 1.0], got " + lowThreshold);
        }
        if (!Double.isFinite(highThreshold) || highThreshold < 0.0 || highThreshold > 1.0) {
            throw new IllegalArgumentException("highThreshold must be finite and in [0.0, 1.0], got " + highThreshold);
        }
        if (lowThreshold > highThreshold) {
            throw new IllegalArgumentException(
                    "lowThreshold (" + lowThreshold + ") must not exceed highThreshold (" + highThreshold + ")");
        }
    }

    public boolean isLow(double confidence) {
        return confidence < lowThreshold;
    }

    public boolean isReview(double confidence) {
        return confidence >= lowThreshold && confidence < highThreshold;
    }

    public boolean isHigh(double confidence) {
        return confidence >= highThreshold;
    }
}
