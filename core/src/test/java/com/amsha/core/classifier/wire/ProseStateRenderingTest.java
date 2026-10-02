package com.amsha.core.classifier.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.amsha.classifier.api.DecisionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Pins the per-provider {@code state} dialect.
 *
 * <p>Two providers can share the {@code POST /v1/systemone} route and still disagree about
 * {@code state}: TypeSafe, Laya, and Kev take a JSON string, while CLM's contrastive heads were
 * trained on prose. Sending the wrong one is not rejected — it degrades answers silently — so these
 * tests exist to make the distinction explicit and to prove the prose path fails closed rather than
 * emitting something a provider would silently score badly.
 */
class ProseStateRenderingTest {

    private static final String STATE = "{\"ticket\":\"charged twice\"}";

    private final ObjectMapper mapper = new ObjectMapper();

    private static DecisionRequest choiceRequest() {
        var builder = DecisionRequest.builder("dec.route", 1, STATE);
        builder.registerChoice("department", "Which team should handle this?", List.of("billing", "technical"));
        return builder.build();
    }

    /** Maps with an explicit profile and returns the parsed body. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapWith(WireProfile profile, String projectedStateJson) throws Exception {
        var outcome = SystemOneWireMapper.map(mapper, choiceRequest(), projectedStateJson, "clm-latest", profile);
        assertInstanceOf(SystemOneWireMapper.Outcome.Ready.class, outcome, () -> String.valueOf(outcome));
        return mapper.readValue(((SystemOneWireMapper.Outcome.Ready) outcome).body(), Map.class);
    }

    /** Renders one projection as prose through the mapper, so the wiring is exercised, not just the renderer. */
    private String proseOf(String projectedStateJson) throws Exception {
        return (String) mapWith(WireProfile.PROSE, projectedStateJson).get("state");
    }

    private SystemOneWireMapper.Outcome.Unrepresentable refuse(String projectedStateJson) {
        var outcome =
                SystemOneWireMapper.map(mapper, choiceRequest(), projectedStateJson, "clm-latest", WireProfile.PROSE);
        return assertInstanceOf(SystemOneWireMapper.Outcome.Unrepresentable.class, outcome);
    }

    @Test
    void rendersObjectFieldsAsKeyValueLines() throws Exception {
        assertEquals("ticket: charged twice", proseOf("{\"ticket\":\"charged twice\"}"));
    }

    @Test
    void rendersNestedObjectsWithTwoSpaceIndentation() throws Exception {
        assertEquals("customer:\n  name: Ada", proseOf("{\"customer\":{\"name\":\"Ada\"}}"));
    }

    @Test
    void rendersArraysAsDashItems() throws Exception {
        assertEquals("tags:\n  - vip\n  - refund", proseOf("{\"tags\":[\"vip\",\"refund\"]}"));
    }

    @Test
    void rendersAContainerArrayItemAsABareMarkerThenItsFields() throws Exception {
        assertEquals("items:\n  -\n    a: 1", proseOf("{\"items\":[{\"a\":1}]}"));
    }

    @Test
    void rendersJsonNullExplicitlyRatherThanAsAnEmptyString() throws Exception {
        assertEquals("note: null", proseOf("{\"note\":null}"));
    }

    @Test
    void rendersNumbersAndBooleansAsText() throws Exception {
        assertEquals("count: 3\nurgent: true", proseOf("{\"count\":3,\"urgent\":true}"));
    }

    @Test
    void preservesProjectionFieldOrder() throws Exception {
        assertEquals("b: 2\na: 1", proseOf("{\"b\":2,\"a\":1}"));
    }

    @Test
    void keepsTheJsonStringUnchangedForTheJsonObjectProfile() throws Exception {
        Map<String, Object> payload = mapWith(WireProfile.JSON_OBJECT, STATE);

        assertEquals(STATE, payload.get("state"), "the JSON dialect must send the projection verbatim");
    }

    @Test
    void theFourArgumentOverloadStillDefaultsToTheJsonDialect() throws Exception {
        var outcome = SystemOneWireMapper.map(mapper, choiceRequest(), STATE, "jev-latest");

        var ready = assertInstanceOf(SystemOneWireMapper.Outcome.Ready.class, outcome);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = mapper.readValue(ready.body(), Map.class);
        assertEquals(STATE, payload.get("state"));
    }

    @Test
    void failsClosedWhenTheProjectionIsNotJson() {
        var unrepresentable = refuse("ticket: charged twice");

        assertTrue(unrepresentable.reason().contains("prose"), unrepresentable.reason());
    }

    @Test
    void failsClosedWhenTheProjectionRendersToNothing() {
        var unrepresentable = refuse("{}");

        assertTrue(unrepresentable.reason().contains("prose"), unrepresentable.reason());
    }

    @Test
    void theRefusalReasonDoesNotEchoTheProjection() {
        var unrepresentable = refuse("{\"secret\":\"do-not-log\"");

        assertFalse(unrepresentable.reason().contains("do-not-log"), unrepresentable.reason());
    }

    @Test
    void theProfileChangesOnlyStateAndNotTheQuestions() throws Exception {
        Map<String, Object> asProse = mapWith(WireProfile.PROSE, "{\"ticket\":\"charged twice\"}");
        Map<String, Object> asJson = mapWith(WireProfile.JSON_OBJECT, "{\"ticket\":\"charged twice\"}");

        assertEquals(asJson.get("questions"), asProse.get("questions"));
        assertEquals("clm-latest", asProse.get("model"));
    }
}
