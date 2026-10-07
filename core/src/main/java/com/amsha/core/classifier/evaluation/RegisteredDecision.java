package com.amsha.core.classifier.evaluation;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.amsha.classifier.api.DecisionDefinition;
import com.amsha.classifier.api.DecisionQuestion;

/**
 * A decision the evaluation path is permitted to run: the security-shaped {@link DecisionDefinition}
 * plus the questions it asks.
 *
 * <p>The pair is deliberate. A {@code DecisionDefinition} alone is a policy envelope with no question
 * in it, and a question list alone has no retention, redaction, or provider bound. Only together do
 * they describe a decision that can be evaluated without the caller inventing either half.
 *
 * <p><b>The options a caller may supply are exactly the ones a registered question delegates.</b>
 * {@link #optionsProblem} is the one rule that answers "did this request name the right closed sets?",
 * and it is consulted by the service before any provider is built. It is pure and payload-free: a
 * reason names handles, never option values.
 *
 * @param definition the registered definition (primitive, criteria, redaction, retention,
 *     allowedProviders, failMode, timeout)
 * @param questions the atomic questions this decision asks
 */
public record RegisteredDecision(DecisionDefinition definition, List<QuestionSpec> questions) {

    public RegisteredDecision {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(questions, "questions");
        if (questions.isEmpty()) {
            throw new IllegalArgumentException(
                    "a registered decision must declare at least one question; '" + definition.decisionId() + "'");
        }
        questions = List.copyOf(questions);
        Set<String> seen = new HashSet<>();
        for (QuestionSpec question : questions) {
            Objects.requireNonNull(question, "question");
            if (!seen.add(question.handle())) {
                throw new IllegalArgumentException(
                        "duplicate question handle '" + question.handle() + "' in '" + definition.decisionId() + "'");
            }
        }
    }

    /** The registered decision id. */
    public String decisionId() {
        return definition.decisionId();
    }

    /**
     * The reason a request's supplied choice options do not match this decision, if any.
     *
     * <p>An option set is required for exactly the caller-supplied handles and forbidden for every
     * other one. The count is bounded here (2..255) as well as by {@code DecisionQuestion}, so a
     * malformed request is refused as a client error instead of surfacing as a construction failure.
     *
     * @param supplied options per question handle, as supplied by the request
     * @return the refusal reason, or empty when the supplied options match the registered questions
     */
    public Optional<String> optionsProblem(Map<String, List<String>> supplied) {
        Map<String, List<String>> options = supplied == null ? Map.of() : supplied;
        Set<String> callerHandles = new LinkedHashSet<>();
        for (QuestionSpec question : questions) {
            if (question.callerSupplied()) {
                callerHandles.add(question.handle());
            }
        }
        for (String handle : options.keySet()) {
            if (!callerHandles.contains(handle)) {
                return Optional.of("Decision '" + decisionId() + "' does not accept caller-supplied options for '"
                        + handle + "'; its registered questions fix those options");
            }
        }
        for (String handle : callerHandles) {
            List<String> values = options.get(handle);
            if (values == null || values.size() < 2) {
                return Optional.of("Decision '" + decisionId() + "' requires at least 2 options for the"
                        + " caller-supplied question '" + handle + "'");
            }
            if (values.size() > DecisionQuestion.MAX_CHOICE_OPTIONS) {
                return Optional.of("Decision '" + decisionId() + "' received " + values.size()
                        + " options for '" + handle + "', exceeding the ceiling of "
                        + DecisionQuestion.MAX_CHOICE_OPTIONS);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "RegisteredDecision[id=" + definition.decisionId()
                + ", schemaVersion=" + definition.schemaVersion()
                + ", questions=" + questions.size() + "]";
    }
}
