package com.amsha.core.classifier.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Validation contract for the one caller-supplied evaluation request.
 *
 * <p>Every malformed field must surface as an {@link IllegalArgumentException} — a client error the resource
 * maps to 400 — rather than as a {@link NullPointerException} that would become a 500. The resource only
 * catches {@code IllegalArgumentException} (plus a defensive {@code NullPointerException}), so a thrown NPE
 * here would be a regression of that contract.
 */
class DecisionEvaluationRequestTest {

    @Test
    void buildsWithValidFields() {
        DecisionEvaluationRequest req = DecisionEvaluationRequest.of(
                "amsha.routing.intent.v1",
                "{\"user_message\":\"hi\"}",
                Map.of("intent_role_choice", List.of("coder", "researcher")));
        assertEquals("amsha.routing.intent.v1", req.decisionId());
        assertEquals(1, req.choiceOptions().size());
    }

    @Test
    void nullDecisionIdIsAClientErrorNotAServerFault() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvaluationRequest(null, "{}", Map.of(), Optional.empty()));
    }

    @Test
    void blankDecisionIdIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvaluationRequest("  ", "{}", Map.of(), Optional.empty()));
    }

    @Test
    void nullStateJsonIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvaluationRequest("id", null, Map.of(), Optional.empty()));
    }

    @Test
    void blankStateJsonIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvaluationRequest("id", "  ", Map.of(), Optional.empty()));
    }

    @Test
    void nullOptionKeyIsRejectedRatherThanThrowingNpe() {
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put(null, List.of("a", "b"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvaluationRequest("id", "{}", options, Optional.empty()));
    }

    @Test
    void nullOptionValueIsRejected() {
        // Arrays.asList permits a null element, unlike List.of, so the null reaches the constructor as a value.
        Map<String, List<String>> options = Map.of("h", Arrays.asList("a", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvaluationRequest("id", "{}", options, Optional.empty()));
    }

    @Test
    void blankOptionValueIsRejected() {
        Map<String, List<String>> options = Map.of("h", List.of("a", " "));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvaluationRequest("id", "{}", options, Optional.empty()));
    }
}
