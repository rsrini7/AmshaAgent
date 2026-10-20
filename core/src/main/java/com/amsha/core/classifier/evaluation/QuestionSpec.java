package com.amsha.core.classifier.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.amsha.classifier.api.DecisionPrimitive;

/**
 * One atomic question declared by a {@link RegisteredDecision}.
 *
 * <p>A registered decision is the security-shaped half of an evaluation: it owns the retention,
 * redaction, provider and fail-mode policies that bound egress. This type owns the other half — what
 * is actually asked — and it exists so that "the options are fixed by the registry" and "the caller
 * supplies the options" are two distinct, explicit shapes rather than one field whose emptiness is
 * overloaded to mean both.
 *
 * <p><b>{@code callerSupplied} is the only widening the registry permits.</b> A registered decision
 * whose question is a closed {@code CHOICE} may let the caller name the admissible labels, because
 * those labels are runtime data (the router's {@code validRoles}) rather than a policy. Nothing else
 * about a question — its handle, its criteria, its primitive — may come from a request.
 *
 * @param handle the question handle the engine answers by; unique within its decision
 * @param kind the decision primitive the question asks for
 * @param criteria the evaluation instructions the provider receives
 * @param fixedOptions the closed option list for a fixed {@code CHOICE}; empty otherwise
 * @param requiredOptions registry-owned options appended to caller-supplied choices; empty otherwise
 * @param callerSupplied whether the request supplies this question's options instead of the registry
 */
public record QuestionSpec(
        String handle,
        DecisionPrimitive kind,
        String criteria,
        List<String> fixedOptions,
        List<String> requiredOptions,
        boolean callerSupplied) {

    public QuestionSpec {
        Objects.requireNonNull(handle, "handle");
        if (handle.isBlank()) {
            throw new IllegalArgumentException("question handle must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(criteria, "criteria");
        if (criteria.isBlank()) {
            throw new IllegalArgumentException("question criteria must not be blank");
        }
        fixedOptions = fixedOptions == null ? List.of() : List.copyOf(fixedOptions);
        requiredOptions = requiredOptions == null ? List.of() : List.copyOf(requiredOptions);
        for (String option : fixedOptions) {
            if (option == null || option.isBlank()) {
                throw new IllegalArgumentException("fixed option for question '" + handle + "' must not be blank");
            }
        }
        for (String option : requiredOptions) {
            if (option == null || option.isBlank()) {
                throw new IllegalArgumentException("required option for question '" + handle + "' must not be blank");
            }
        }
        if (new HashSet<>(requiredOptions).size() != requiredOptions.size()) {
            throw new IllegalArgumentException("required options for question '" + handle + "' must be unique");
        }
        if (callerSupplied) {
            // Only a closed CHOICE can have its options delegated: a SCORE rubric or a NOUL proposition
            // is not a closed set, so there is nothing for a caller to widen and the combination is
            // refused rather than silently accepted.
            if (kind != DecisionPrimitive.CHOICE) {
                throw new IllegalArgumentException(
                        "only a CHOICE question may be caller-supplied, got " + kind + " for '" + handle + "'");
            }
            if (!fixedOptions.isEmpty()) {
                throw new IllegalArgumentException(
                        "a caller-supplied question must declare no fixed options, got '" + handle + "'");
            }
        } else if (kind == DecisionPrimitive.CHOICE) {
            if (!requiredOptions.isEmpty()) {
                throw new IllegalArgumentException(
                        "a fixed CHOICE cannot declare caller-supplied required options, got '" + handle + "'");
            }
            if (fixedOptions.size() < 2) {
                throw new IllegalArgumentException("a fixed CHOICE question requires at least 2 options, got "
                        + fixedOptions.size() + " for '" + handle + "'");
            }
        } else if (!fixedOptions.isEmpty()) {
            throw new IllegalArgumentException(
                    "only a CHOICE question may declare options, got " + kind + " for '" + handle + "'");
        } else if (!requiredOptions.isEmpty()) {
            throw new IllegalArgumentException("only a caller-supplied CHOICE may declare required options, got " + kind
                    + " for '" + handle + "'");
        }
    }

    /**
     * A closed {@code CHOICE} whose admissible labels come from the request.
     *
     * @param handle the question handle
     * @param criteria the evaluation instructions
     * @return a caller-supplied choice question
     */
    public static QuestionSpec callerSupplied(String handle, String criteria) {
        return callerSupplied(handle, criteria, List.of());
    }

    /**
     * A {@code CHOICE} whose caller supplies dynamic options and the registry supplies required options.
     *
     * @param handle question handle
     * @param criteria evaluation instructions
     * @param requiredOptions options always appended by the registry
     * @return caller-supplied choice question
     */
    public static QuestionSpec callerSupplied(String handle, String criteria, List<String> requiredOptions) {
        return new QuestionSpec(handle, DecisionPrimitive.CHOICE, criteria, List.of(), requiredOptions, true);
    }

    /**
     * A {@code CHOICE} whose admissible labels are fixed by the registry.
     *
     * @param handle the question handle
     * @param criteria the evaluation instructions
     * @param options the closed option list
     * @return a fixed choice question
     */
    public static QuestionSpec fixedChoice(String handle, String criteria, List<String> options) {
        return new QuestionSpec(handle, DecisionPrimitive.CHOICE, criteria, options, List.of(), false);
    }

    /**
     * A {@code SCORE} or {@code NOUL} question, which carries no options.
     *
     * @param handle the question handle
     * @param kind the primitive ({@code SCORE} or {@code NOUL})
     * @param criteria the evaluation instructions
     * @return a fixed non-choice question
     */
    public static QuestionSpec fixed(String handle, DecisionPrimitive kind, String criteria) {
        return new QuestionSpec(handle, kind, criteria, List.of(), List.of(), false);
    }

    @Override
    public String toString() {
        return "QuestionSpec[handle=" + handle + ", kind=" + kind
                + (callerSupplied ? ", callerSupplied" : ", options=" + fixedOptions.size()) + "]";
    }
}
