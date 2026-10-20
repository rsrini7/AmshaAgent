package com.amsha.classifier.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Specification of an atomic decision question asked against bounded state.
 * Validates cross-field combinations based on the target primitive:
 * <ul>
 *   <li>Choice questions require 2 to 255 unique options and no rubric levels.</li>
 *   <li>Score questions require no choice options, and at least 2 rubric levels if levels are specified.</li>
 *   <li>Noul questions require no choice options and no rubric levels.</li>
 * </ul>
 *
 * @param <A> the typed answer produced for this question
 * @param key typed question handle
 * @param criteria evaluation instructions/rubric criteria
 * @param options closed choice options (Choice only)
 * @param rubricLevels ordered rubric descriptions (Score only)
 */
public record DecisionQuestion<A extends DecisionAnswer>(
        QuestionKey<A> key, String criteria, List<String> options, List<String> rubricLevels) {

    public static final int MAX_CHOICE_OPTIONS = 255;

    public DecisionQuestion {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(criteria, "criteria");
        if (criteria.isBlank()) {
            throw new IllegalArgumentException("criteria must not be blank");
        }

        if (options == null) {
            options = List.of();
        } else {
            List<String> optCopy = new ArrayList<>(options.size());
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < options.size(); i++) {
                String opt = Objects.requireNonNull(options.get(i), "options[" + i + "]");
                if (opt.isBlank()) {
                    throw new IllegalArgumentException("option at index " + i + " must not be blank");
                }
                if (!seen.add(opt)) {
                    throw new IllegalArgumentException("duplicate option '" + opt + "' in choice question");
                }
                optCopy.add(opt);
            }
            options = Collections.unmodifiableList(optCopy);
        }

        if (rubricLevels == null) {
            rubricLevels = List.of();
        } else {
            List<String> rubCopy = new ArrayList<>(rubricLevels.size());
            for (int i = 0; i < rubricLevels.size(); i++) {
                String rub = Objects.requireNonNull(rubricLevels.get(i), "rubricLevels[" + i + "]");
                if (rub.isBlank()) {
                    throw new IllegalArgumentException("rubricLevel at index " + i + " must not be blank");
                }
                rubCopy.add(rub);
            }
            rubricLevels = Collections.unmodifiableList(rubCopy);
        }

        DecisionPrimitive primitive = key.primitive();
        switch (primitive) {
            case CHOICE -> {
                if (options.size() < 2) {
                    throw new IllegalArgumentException(
                            "Choice question requires at least 2 options, got " + options.size());
                }
                if (options.size() > MAX_CHOICE_OPTIONS) {
                    throw new IllegalArgumentException("Choice question option count (" + options.size()
                            + ") exceeds ceiling of " + MAX_CHOICE_OPTIONS);
                }
                if (!rubricLevels.isEmpty()) {
                    throw new IllegalArgumentException("Choice question must not define rubric levels");
                }
            }
            case SCORE -> {
                if (!options.isEmpty()) {
                    throw new IllegalArgumentException("Score question must not define choice options");
                }
                if (!rubricLevels.isEmpty() && rubricLevels.size() < 2) {
                    throw new IllegalArgumentException(
                            "Score question rubricLevels must have at least 2 levels if specified, got "
                                    + rubricLevels.size());
                }
            }
            case NOUL -> {
                if (!options.isEmpty()) {
                    throw new IllegalArgumentException("Noul question must not define choice options");
                }
                if (!rubricLevels.isEmpty()) {
                    throw new IllegalArgumentException("Noul question must not define rubric levels");
                }
            }
        }
    }

    public static DecisionQuestion<Choice> choice(QuestionKey<Choice> key, String criteria, List<String> options) {
        return new DecisionQuestion<>(key, criteria, options, List.of());
    }

    public static DecisionQuestion<Choice> choice(String name, String criteria, List<String> options) {
        return new DecisionQuestion<>(QuestionKey.choice(name), criteria, options, List.of());
    }

    public static DecisionQuestion<Score> score(QuestionKey<Score> key, String criteria, List<String> rubricLevels) {
        return new DecisionQuestion<>(key, criteria, List.of(), rubricLevels);
    }

    public static DecisionQuestion<Score> score(String name, String criteria, List<String> rubricLevels) {
        return new DecisionQuestion<>(QuestionKey.score(name), criteria, List.of(), rubricLevels);
    }

    public static DecisionQuestion<Noul> noul(QuestionKey<Noul> key, String criteria) {
        return new DecisionQuestion<>(key, criteria, List.of(), List.of());
    }

    public static DecisionQuestion<Noul> noul(String name, String criteria) {
        return new DecisionQuestion<>(QuestionKey.noul(name), criteria, List.of(), List.of());
    }
}
