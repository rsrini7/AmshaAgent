package com.amsha.core.classifier.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.classifier.api.DecisionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Pins the System One request mapping: the two vocabularies must not be conflated, Amsha-internal
 * fields must not leak onto the wire, and a question with no valid contract representation must be
 * refused rather than sent.
 */
class SystemOneWireMapperTest {

    private static final String STATE = "{\"ticket\":\"charged twice\"}";

    private final ObjectMapper mapper = new ObjectMapper();

    private static DecisionRequest choiceRequest() {
        var builder = DecisionRequest.builder("dec.route", 1, STATE);
        builder.registerChoice("department", "Which team should handle this?", List.of("billing", "technical"));
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapRequest(DecisionRequest request, String modelId) throws Exception {
        var outcome = SystemOneWireMapper.map(mapper, request, STATE, modelId);
        assertInstanceOf(SystemOneWireMapper.Outcome.Ready.class, outcome);
        return mapper.readValue(((SystemOneWireMapper.Outcome.Ready) outcome).body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> question(Map<String, Object> payload, String handle) {
        return (Map<String, Object>) ((Map<String, Object>) payload.get("questions")).get(handle);
    }

    @Test
    void mapsChoiceInstructionAndOptionsOntoContractVocabulary() throws Exception {
        Map<String, Object> payload = mapRequest(choiceRequest(), "jev-1.13.0");

        assertEquals("jev-1.13.0", payload.get("model"));
        assertEquals(STATE, payload.get("state"));

        Map<String, Object> mapped = question(payload, "department");
        assertEquals("choice", mapped.get("type"));
        // Amsha's free-text criteria is the contract's instructions, not its criteria.
        assertEquals("Which team should handle this?", mapped.get("instructions"));

        @SuppressWarnings("unchecked")
        Map<String, Object> criteria = (Map<String, Object>) mapped.get("criteria");
        assertEquals(Set.of("billing", "technical"), criteria.keySet());
        assertNull(criteria.get("billing"));
    }

    @Test
    void choiceCriteriaSerializesNullOptionDetail() {
        var outcome = SystemOneWireMapper.map(mapper, choiceRequest(), STATE, "jev-latest");

        String body = ((SystemOneWireMapper.Outcome.Ready) outcome).body();
        assertTrue(body.contains("\"billing\":null"), body);
        assertTrue(body.contains("\"technical\":null"), body);
    }

    @Test
    void omitsAmshaInternalFieldsThatAreNotInTheContract() throws Exception {
        Map<String, Object> payload = mapRequest(choiceRequest(), "jev-latest");

        assertFalse(payload.containsKey("decisionId"), "decisionId is not part of the contract");
        assertFalse(payload.containsKey("schemaVersion"), "schemaVersion is not part of the contract");
        assertFalse(payload.containsKey("timeoutMs"), "timeoutMs is not part of the contract");
        assertEquals(Set.of("state", "model", "questions"), payload.keySet());
    }

    @Test
    void mapsScoreRubricToOrderedCriteriaArray() throws Exception {
        var builder = DecisionRequest.builder("dec.score", 1, STATE);
        builder.registerScore("frustration", "How frustrated?", List.of("Calm", "Frustrated", "Very angry"));

        Map<String, Object> mapped = question(mapRequest(builder.build(), "jev-latest"), "frustration");
        assertEquals("score", mapped.get("type"));
        assertEquals("How frustrated?", mapped.get("instructions"));
        assertEquals(List.of("Calm", "Frustrated", "Very angry"), mapped.get("criteria"));
    }

    @Test
    void mapsNoulWithoutACriteriaBlock() throws Exception {
        var builder = DecisionRequest.builder("dec.noul", 1, STATE);
        builder.registerNoul("is_urgent", "Does this convey urgency?");

        Map<String, Object> mapped = question(mapRequest(builder.build(), "jev-latest"), "is_urgent");
        assertEquals("noul", mapped.get("type"));
        assertEquals("Does this convey urgency?", mapped.get("instructions"));
        assertFalse(mapped.containsKey("criteria"), "noul criteria is optional and must not be invented");
    }

    @Test
    void mapsMixedPrimitivesInOneRequest() throws Exception {
        var builder = DecisionRequest.builder("dec.mixed", 1, STATE);
        builder.registerChoice("department", "Which team?", List.of("billing", "technical"));
        builder.registerScore("frustration", "How frustrated?", List.of("Calm", "Angry"));
        builder.registerNoul("is_urgent", "Urgent?");

        Map<String, Object> payload = mapRequest(builder.build(), "jev-latest");

        @SuppressWarnings("unchecked")
        Map<String, Object> questions = (Map<String, Object>) payload.get("questions");
        assertEquals(3, questions.size());
        assertEquals("choice", question(payload, "department").get("type"));
        assertEquals("score", question(payload, "frustration").get("type"));
        assertEquals("noul", question(payload, "is_urgent").get("type"));
    }

    @Test
    void omitsModelWhenNoIdentifierIsConfigured() throws Exception {
        Map<String, Object> payload = mapRequest(choiceRequest(), "   ");

        assertFalse(payload.containsKey("model"), "a blank identifier must be omitted, not sent empty");
        assertEquals(Set.of("state", "questions"), payload.keySet());
    }

    @Test
    void refusesScoreOutsideContractLevelBounds() {
        var tooFew = DecisionRequest.builder("dec.few", 1, STATE);
        tooFew.registerScore("quality", "Rate", List.of());
        assertInstanceOf(
                SystemOneWireMapper.Outcome.Unrepresentable.class,
                SystemOneWireMapper.map(mapper, tooFew.build(), STATE, "jev-latest"));

        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add("level-" + i);
        }
        var tooMany = DecisionRequest.builder("dec.many", 1, STATE);
        tooMany.registerScore("quality", "Rate", eleven);
        assertInstanceOf(
                SystemOneWireMapper.Outcome.Unrepresentable.class,
                SystemOneWireMapper.map(mapper, tooMany.build(), STATE, "jev-latest"));
    }

    @Test
    void unrepresentableReasonNamesTheHandleWithoutLeakingState() {
        var builder = DecisionRequest.builder("dec.empty", 1, "{\"secret\":\"do-not-log\"}");
        builder.registerScore("quality", "Rate", List.of());

        var outcome = SystemOneWireMapper.map(mapper, builder.build(), "{\"secret\":\"do-not-log\"}", "jev-latest");
        var unrepresentable = assertInstanceOf(SystemOneWireMapper.Outcome.Unrepresentable.class, outcome);

        assertTrue(unrepresentable.reason().contains("quality"), unrepresentable.reason());
        assertFalse(unrepresentable.reason().contains("do-not-log"), unrepresentable.reason());
    }
}
