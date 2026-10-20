package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DecisionRequest} — the bounded request contract, and the place where a question handle's
 * *owner* is bound.
 *
 * <p>The security-relevant half is the handle identity check. {@code Builder.register} does not store the
 * caller's {@link QuestionKey}; it stores a copy rebound to a per-builder identity token
 * ({@code question.key().withOwner(owner)}), and {@link QuestionKey#equals} compares that token. So two keys
 * with the same name but different owners are <em>different</em> keys, and {@link DecisionRequest#question}
 * and {@link #contains} must refuse the foreign one. Without that, a handle minted for one request would
 * resolve against another request's question — the classic confused-deputy shape. Both lookups were entirely
 * unasserted (the whole of {@code question(QuestionKey)} and {@code question(String)} was dark), so the
 * rejection could have been removed without a single test failing.
 *
 * <p>The remaining gaps were the constructor's five refusal guards — including the map-key/question-name
 * mismatch check, which stops a handle being registered under a name the request never agreed to — and the
 * four builder policy setters.
 */
class DecisionRequestTest {

    private static final String DECISION_ID = "amsha.test.decision";
    private static final int SCHEMA = 1;
    private static final String STATE = "{\"turn\":1}";

    /** A built request carrying one registered choice question, plus the bound handle for it. */
    private static final class Fixture {
        final DecisionRequest.Builder builder;
        final QuestionKey<Choice> intent;
        final DecisionRequest request;

        Fixture() {
            builder = DecisionRequest.builder(DECISION_ID, SCHEMA, STATE);
            intent = builder.registerChoice("intent", "classify the intent", List.of("research", "chat"));
            request = builder.build();
        }
    }

    private static Map<String, DecisionQuestion<?>> oneQuestion() {
        return Map.of("intent", DecisionQuestion.choice("intent", "classify the intent", List.of("research", "chat")));
    }

    private static DecisionRequest construct(
            String decisionId, int schemaVersion, Map<String, DecisionQuestion<?>> questions, Duration timeout) {
        return new DecisionRequest(
                decisionId,
                schemaVersion,
                STATE,
                questions,
                timeout,
                ProviderCategory.IN_PROCESS,
                RedactionPolicy.DEFAULT,
                RetentionPolicy.EPHEMERAL);
    }

    // ---------------------------------------------------------------- owner binding

    @Test
    void registerReturnsTheExactHandleTheRequestStores() {
        Fixture fixture = new Fixture();

        assertEquals(Set.of("intent"), fixture.request.questions().keySet());
        assertSame(
                fixture.intent,
                fixture.request.questions().get("intent").key(),
                "the handle handed to the caller must be the one the request stored, not a look-alike");
    }

    @Test
    void questionByKeyReturnsTheRegisteredSpecification() {
        Fixture fixture = new Fixture();

        DecisionQuestion<Choice> spec = fixture.request.question(fixture.intent);

        assertSame(fixture.intent, spec.key());
        assertEquals("classify the intent", spec.criteria());
        assertEquals(List.of("research", "chat"), spec.options());
    }

    @Test
    void questionByKeyRefusesAHandleMintedByAnotherRequest() {
        Fixture mine = new Fixture();
        // Same decision id, same schema, same question name — the only difference is the owner token.
        Fixture theirs = new Fixture();

        assertEquals("intent", theirs.intent.name(), "the two handles must be indistinguishable by name alone");

        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> mine.request.question(theirs.intent))
                        .getMessage()
                        .contains("Unknown question handle"),
                "a handle bound to a different request must not resolve here");
        assertFalse(mine.request.contains(theirs.intent), "and contains() must agree with question()");
    }

    @Test
    void questionByKeyRefusesAnUnboundHandleForABoundQuestion() {
        Fixture fixture = new Fixture();

        // A fresh QuestionKey.choice("intent") has owner == null, so it is not the registered key.
        assertTrue(assertThrows(
                        IllegalArgumentException.class, () -> fixture.request.question(QuestionKey.choice("intent")))
                .getMessage()
                .contains("Unknown question handle"));
    }

    @Test
    void questionByKeyRefusesAnUnknownNameAndANullHandle() {
        Fixture fixture = new Fixture();

        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> fixture.request.question(QuestionKey.choice("nope")))
                        .getMessage()
                        .contains("Unknown question handle"));
        assertEquals(
                "key",
                assertThrows(NullPointerException.class, () -> fixture.request.question((QuestionKey<Choice>) null))
                        .getMessage());
    }

    @Test
    void questionByNameResolvesTheSpecificationAndRefusesUnknownNames() {
        Fixture fixture = new Fixture();

        assertEquals("classify the intent", fixture.request.question("intent").criteria());

        assertTrue(assertThrows(IllegalArgumentException.class, () -> fixture.request.question("nope"))
                .getMessage()
                .contains("Unknown question handle name"));
        assertEquals(
                "name",
                assertThrows(NullPointerException.class, () -> fixture.request.question((String) null))
                        .getMessage());
    }

    @Test
    void containsAcceptsOnlyTheOwnerBoundHandle() {
        Fixture fixture = new Fixture();

        assertTrue(fixture.request.contains(fixture.intent));
        assertFalse(fixture.request.contains(QuestionKey.choice("intent")), "right name, no owner token");
        assertFalse(fixture.request.contains(QuestionKey.choice("other")), "unknown name");
        assertFalse(fixture.request.contains(null));
    }

    // ---------------------------------------------------------------- constructor guards

    @Test
    void constructorRefusesAnInvalidIdentityShapeOrTimeout() {
        Map<String, DecisionQuestion<?>> questions = oneQuestion();

        assertTrue(assertThrows(
                        IllegalArgumentException.class, () -> construct("  ", SCHEMA, questions, Duration.ofSeconds(5)))
                .getMessage()
                .contains("decisionId must not be blank"));
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> construct(DECISION_ID, 0, questions, Duration.ofSeconds(5)))
                .getMessage()
                .contains("schemaVersion must be >= 1"));
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> construct(DECISION_ID, SCHEMA, Map.of(), Duration.ofSeconds(5)))
                .getMessage()
                .contains("at least one question"));
        assertTrue(assertThrows(
                        IllegalArgumentException.class, () -> construct(DECISION_ID, SCHEMA, questions, Duration.ZERO))
                .getMessage()
                .contains("timeout must be positive"));
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> construct(DECISION_ID, SCHEMA, questions, Duration.ofSeconds(-1)))
                .getMessage()
                .contains("timeout must be positive"));
    }

    @Test
    void constructorRefusesAMapKeyThatDoesNotMatchItsQuestionName() {
        // The map key is the lookup name; the question's own handle name is what identity checks use. If the two
        // diverge, a caller could look up a handle under a name the request never registered.
        Map<String, DecisionQuestion<?>> mismatched = Map.of(
                "wrong-name", DecisionQuestion.choice("intent", "classify the intent", List.of("research", "chat")));

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> construct(DECISION_ID, SCHEMA, mismatched, Duration.ofSeconds(5)));

        assertTrue(thrown.getMessage().contains("Key name mismatch"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("wrong-name"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("intent"), thrown.getMessage());
    }

    @Test
    void constructorNamesTheComponentThatIsNull() {
        Map<String, DecisionQuestion<?>> questions = oneQuestion();
        Duration timeout = Duration.ofSeconds(5);

        assertEquals(
                "decisionId",
                assertThrows(NullPointerException.class, () -> construct(null, SCHEMA, questions, timeout))
                        .getMessage());
        assertEquals(
                "state",
                assertThrows(
                                NullPointerException.class,
                                () -> new DecisionRequest(
                                        DECISION_ID,
                                        SCHEMA,
                                        null,
                                        questions,
                                        timeout,
                                        ProviderCategory.IN_PROCESS,
                                        RedactionPolicy.DEFAULT,
                                        RetentionPolicy.EPHEMERAL))
                        .getMessage());
        assertEquals(
                "questions",
                assertThrows(NullPointerException.class, () -> construct(DECISION_ID, SCHEMA, null, timeout))
                        .getMessage());
        assertEquals(
                "timeout",
                assertThrows(NullPointerException.class, () -> construct(DECISION_ID, SCHEMA, questions, null))
                        .getMessage());
        assertEquals(
                "targetCategory",
                assertThrows(
                                NullPointerException.class,
                                () -> new DecisionRequest(
                                        DECISION_ID,
                                        SCHEMA,
                                        STATE,
                                        questions,
                                        timeout,
                                        null,
                                        RedactionPolicy.DEFAULT,
                                        RetentionPolicy.EPHEMERAL))
                        .getMessage());
        assertEquals(
                "redactionPolicy",
                assertThrows(
                                NullPointerException.class,
                                () -> new DecisionRequest(
                                        DECISION_ID,
                                        SCHEMA,
                                        STATE,
                                        questions,
                                        timeout,
                                        ProviderCategory.IN_PROCESS,
                                        null,
                                        RetentionPolicy.EPHEMERAL))
                        .getMessage());
        assertEquals(
                "retentionPolicy",
                assertThrows(
                                NullPointerException.class,
                                () -> new DecisionRequest(
                                        DECISION_ID,
                                        SCHEMA,
                                        STATE,
                                        questions,
                                        timeout,
                                        ProviderCategory.IN_PROCESS,
                                        RedactionPolicy.DEFAULT,
                                        null))
                        .getMessage());
    }

    // ---------------------------------------------------------------- builder policy setters

    @Test
    void builderSettersReachTheBuiltRequest() {
        // Asserting only that the setters null-check would pass even if they never assigned the field.
        RedactionPolicy redaction = new RedactionPolicy(false, true, Set.of("ssn"));
        RetentionPolicy retention = new RetentionPolicy(Duration.ofHours(1), false, false);

        Fixture fixture = new Fixture();
        DecisionRequest request = fixture.builder
                .timeout(Duration.ofSeconds(30))
                .targetCategory(ProviderCategory.REMOTE)
                .redactionPolicy(redaction)
                .retentionPolicy(retention)
                .build();

        assertEquals(Duration.ofSeconds(30), request.timeout());
        assertEquals(ProviderCategory.REMOTE, request.targetCategory());
        assertSame(redaction, request.redactionPolicy());
        assertSame(retention, request.retentionPolicy());
    }

    @Test
    void builderSettersRefuseNull() {
        Fixture fixture = new Fixture();

        assertEquals(
                "timeout",
                assertThrows(NullPointerException.class, () -> fixture.builder.timeout(null))
                        .getMessage());
        assertEquals(
                "targetCategory",
                assertThrows(NullPointerException.class, () -> fixture.builder.targetCategory(null))
                        .getMessage());
        assertEquals(
                "redactionPolicy",
                assertThrows(NullPointerException.class, () -> fixture.builder.redactionPolicy(null))
                        .getMessage());
        assertEquals(
                "retentionPolicy",
                assertThrows(NullPointerException.class, () -> fixture.builder.retentionPolicy(null))
                        .getMessage());
    }

    @Test
    void builderDefaultsAreInProcessAndEphemeral() {
        // The defaults are the safe end of each axis: local execution, and no provider retention.
        DecisionRequest request = new Fixture().request;

        assertEquals(ProviderCategory.IN_PROCESS, request.targetCategory());
        assertEquals(RetentionPolicy.EPHEMERAL, request.retentionPolicy());
        assertEquals(RedactionPolicy.DEFAULT, request.redactionPolicy());
        assertEquals(Duration.ofSeconds(5), request.timeout());
    }

    @Test
    void toStringReportsTheHandlesAndTargetCategoryWithoutTheState() {
        Fixture fixture = new Fixture();

        String rendered = fixture.request.toString();

        assertTrue(rendered.contains("decisionId=" + DECISION_ID), rendered);
        assertTrue(rendered.contains("handles=[intent]"), rendered);
        assertTrue(rendered.contains("targetCategory=IN_PROCESS"), rendered);
        assertFalse(rendered.contains(STATE), "the bounded state must not be echoed into a diagnostic");
    }
}
