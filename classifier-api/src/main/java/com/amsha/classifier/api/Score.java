package com.amsha.classifier.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Answer to a score decision question: placement of a subject on an ordered rubric.
 * The scalar score, level probability distribution, and confidence remain distinct;
 * missing fields are never fabricated.
 *
 * @param score numeric placement along rubric levels (from 0.0 to levels - 1.0)
 * @param probabilities level probabilities indexed by level, or empty if omitted
 * @param legend rubric level descriptions, or empty if omitted
 * @param confidence provider confidence in [0.0, 1.0], or empty if omitted
 */
public record Score(double score, List<Double> probabilities, List<String> legend, OptionalDouble confidence)
        implements DecisionAnswer {

    public Score {
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite, got " + score);
        }
        Objects.requireNonNull(confidence, "confidence");
        if (confidence.isPresent()) {
            double conf = confidence.getAsDouble();
            if (!Double.isFinite(conf) || conf < 0.0 || conf > 1.0) {
                throw new IllegalArgumentException("confidence must be finite and in [0.0, 1.0], got " + conf);
            }
        }
        if (probabilities == null) {
            probabilities = List.of();
        } else {
            List<Double> pCopy = new ArrayList<>(probabilities.size());
            for (int i = 0; i < probabilities.size(); i++) {
                Double p = Objects.requireNonNull(probabilities.get(i), "probabilities[" + i + "]");
                if (!Double.isFinite(p) || p < 0.0 || p > 1.0) {
                    throw new IllegalArgumentException(
                            "probabilities[" + i + "] must be finite and in [0.0, 1.0], got " + p);
                }
                pCopy.add(p);
            }
            probabilities = Collections.unmodifiableList(pCopy);
        }
        if (legend == null) {
            legend = List.of();
        } else {
            List<String> lCopy = new ArrayList<>(legend.size());
            for (int i = 0; i < legend.size(); i++) {
                String leg = Objects.requireNonNull(legend.get(i), "legend[" + i + "]");
                if (leg.isBlank()) {
                    throw new IllegalArgumentException("legend[" + i + "] must not be blank");
                }
                lCopy.add(leg);
            }
            legend = Collections.unmodifiableList(lCopy);
        }

        if (!probabilities.isEmpty() && probabilities.size() < 2) {
            throw new IllegalArgumentException(
                    "Score probabilities distribution must have at least 2 levels, got " + probabilities.size());
        }
        if (!legend.isEmpty() && legend.size() < 2) {
            throw new IllegalArgumentException("Score legend must have at least 2 levels, got " + legend.size());
        }
        if (!probabilities.isEmpty() && !legend.isEmpty() && probabilities.size() != legend.size()) {
            throw new IllegalArgumentException("Score probabilities size (" + probabilities.size()
                    + ") does not match legend size (" + legend.size() + ")");
        }

        int levelCount = !probabilities.isEmpty() ? probabilities.size() : (!legend.isEmpty() ? legend.size() : -1);
        if (levelCount > 0) {
            int topLevel = levelCount - 1;
            if (score < 0.0 || score > (double) topLevel) {
                throw new IllegalArgumentException("score must be between 0.0 and " + topLevel + ", got " + score);
            }
        } else if (score < 0.0) {
            throw new IllegalArgumentException("score must be non-negative, got " + score);
        }
    }

    public Score(double score) {
        this(score, List.of(), List.of(), OptionalDouble.empty());
    }

    public Score(double score, OptionalDouble confidence) {
        this(score, List.of(), List.of(), confidence);
    }

    public Score(double score, double confidence) {
        this(score, List.of(), List.of(), OptionalDouble.of(confidence));
    }

    @Override
    public DecisionPrimitive primitive() {
        return DecisionPrimitive.SCORE;
    }

    /**
     * Returns the level with the highest probability, or empty if probabilities were not returned.
     *
     * @return most likely level index or empty
     */
    public OptionalInt mostLikelyLevel() {
        if (probabilities.isEmpty()) {
            return OptionalInt.empty();
        }
        int best = 0;
        for (int i = 1; i < probabilities.size(); i++) {
            if (probabilities.get(i) > probabilities.get(best)) {
                best = i;
            }
        }
        return OptionalInt.of(best);
    }

    /**
     * Returns the score normalized onto [0.0, 1.0] using the rubric level count,
     * or empty if the level count is indeterminate.
     *
     * @return normalized score in [0.0, 1.0] or empty
     */
    public OptionalDouble normalizedScore() {
        int levelCount = !probabilities.isEmpty() ? probabilities.size() : (!legend.isEmpty() ? legend.size() : -1);
        if (levelCount > 1) {
            return OptionalDouble.of(score / (levelCount - 1));
        }
        return OptionalDouble.empty();
    }
}
