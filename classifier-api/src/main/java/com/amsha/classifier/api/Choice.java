package com.amsha.classifier.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Answer to a choice decision question: one selected label from an admissible closed set.
 * Optional probabilities map and confidence preserve provider absence; missing fields
 * must remain absent and never invent uniform distributions or synthetic confidence.
 *
 * @param label the selected option label
 * @param probabilities mapping of option names to probabilities, or empty if omitted by provider
 * @param confidence provider confidence in [0.0, 1.0], or empty if omitted by provider
 */
public record Choice(String label, Map<String, Double> probabilities, OptionalDouble confidence)
        implements DecisionAnswer {

    public Choice {
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("Choice label must not be blank");
        }
        Objects.requireNonNull(confidence, "confidence");
        if (confidence.isPresent()) {
            double conf = confidence.getAsDouble();
            if (!Double.isFinite(conf) || conf < 0.0 || conf > 1.0) {
                throw new IllegalArgumentException("confidence must be finite and in [0.0, 1.0], got " + conf);
            }
        }
        if (probabilities == null) {
            probabilities = Map.of();
        } else {
            Map<String, Double> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
                String opt = Objects.requireNonNull(entry.getKey(), "probability option");
                if (opt.isBlank()) {
                    throw new IllegalArgumentException("option name in probabilities must not be blank");
                }
                Double p = Objects.requireNonNull(entry.getValue(), "probability for " + opt);
                if (!Double.isFinite(p) || p < 0.0 || p > 1.0) {
                    throw new IllegalArgumentException(
                            "probability for '" + opt + "' must be finite and in [0.0, 1.0], got " + p);
                }
                copy.put(opt, p);
            }
            if (!copy.isEmpty() && !copy.containsKey(label)) {
                throw new IllegalArgumentException(
                        "Selected choice '" + label + "' must be present in probabilities map " + copy.keySet());
            }
            probabilities = Collections.unmodifiableMap(copy);
        }
    }

    public Choice(String label) {
        this(label, Map.of(), OptionalDouble.empty());
    }

    public Choice(String label, OptionalDouble confidence) {
        this(label, Map.of(), confidence);
    }

    public Choice(String label, double confidence) {
        this(label, Map.of(), OptionalDouble.of(confidence));
    }

    @Override
    public DecisionPrimitive primitive() {
        return DecisionPrimitive.CHOICE;
    }

    /**
     * Returns the probability for a given option if provided by the engine.
     *
     * @param option option name
     * @return option probability or empty if absent
     */
    public OptionalDouble probability(String option) {
        Double p = probabilities.get(option);
        return p != null ? OptionalDouble.of(p) : OptionalDouble.empty();
    }
}
