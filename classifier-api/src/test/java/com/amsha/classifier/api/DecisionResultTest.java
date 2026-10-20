package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DecisionResult} — the closed result contract every decision consumer switches on.
 *
 * <p>The security-relevant half is {@code NoOpinion.sanitizeDetail}: every diagnostic that reaches a
 * {@code NoOpinion} is run through seven credential-scrubbing patterns before it is stored, so an upstream
 * failure message carrying a token cannot leak into logs or telemetry. The individual patterns were
 * unasserted — only a single combined case was pinned — so a regex could be wrong without any test failing.
 * These tests pin each pattern on its own input.
 *
 * <p>Tokens are assembled from fragments rather than written whole, matching the convention already used in
 * {@code DecisionPlaneEvaluationTest}: a literal that looks like a live credential trips repository secret
 * scanners and would be a false positive.
 */
class DecisionResultTest {

    private static final String DECISION_ID = "amsha.test.decision";
    private static final int SCHEMA = 1;

    private static DecisionResult.NoOpinion noOpinionWithDetail(String detail) {
        return new DecisionResult.NoOpinion(
                DECISION_ID, SCHEMA, ClassifierResult.NoOpinionReason.INFERENCE_ERROR, "rule-1", detail);
    }

    private static String sanitized(String detail) {
        return noOpinionWithDetail(detail).optionalDetail().orElseThrow();
    }

    // ---------------------------------------------------------------- sanitizeDetail

    @Test
    void redactsCredentialsEmbeddedInAUrl() {
        String clean = sanitized("failed calling http://user:" + "p" + "ass@host.local/api");

        assertFalse(clean.contains("pass@host"), clean);
        assertTrue(clean.contains("http://[REDACTED]@host.local/api"), clean);
    }

    @Test
    void redactsATelegramBotApiTokenInTheUrlPath() {
        String token = "123456" + ":AA" + "Hdqwertyuiopasdfghjklzxcvbnm";
        String clean = sanitized("posting https://api.telegram.org/bot" + token + "/sendMessage");

        assertFalse(clean.contains(token), clean);
        assertTrue(clean.contains("https://api.telegram.org/bot[REDACTED]/sendMessage"), clean);
    }

    @Test
    void redactsABareTelegramShapedToken() {
        String token = "123456789" + ":" + "AAHdqwertyuiopasdfghjkl";
        String clean = sanitized("handler rejected token " + token + " for chat 42");

        assertFalse(clean.contains(token), clean);
        assertTrue(clean.contains("[REDACTED]"), clean);
    }

    @Test
    void redactsABearerToken() {
        String token = "abcdefgh" + "12345678";
        String clean = sanitized("upstream 401 with Authorization: Bearer " + token);

        assertFalse(clean.contains(token), clean);
        assertTrue(clean.contains("[REDACTED]"), clean);
    }

    @Test
    void redactsSensitiveKeyValueAssignments() {
        String value = "abcdef" + "1234567890";
        String clean = sanitized("request rejected: api_" + "key=" + value);

        assertFalse(clean.contains(value), clean);
        assertTrue(clean.contains("[REDACTED]"), clean);
    }

    @Test
    void redactsABareProviderApiKey() {
        String key = "sk-" + "abcdefghijklmnop" + "1234";
        String clean = sanitized("provider said the key " + key + " is revoked");

        assertFalse(clean.contains(key), clean);
        assertTrue(clean.contains("[REDACTED]"), clean);
    }

    @Test
    void redactsAJwt() {
        String jwt = "eyJhbGciOi" + "." + "eyJzdWIi" + "." + "SflKxwRJSM";
        String clean = sanitized("token " + jwt + " expired");

        assertFalse(clean.contains(jwt), clean);
        assertTrue(clean.contains("[REDACTED]"), clean);
    }

    @Test
    void truncatesAnOverlongDetailAndMarksTheTruncation() {
        // The only branch that appends the ellipsis: a detail longer than the 128-character budget.
        String clean = sanitized("a".repeat(200));

        assertEquals(131, clean.length(), clean);
        assertEquals("a".repeat(128) + "...", clean);
    }

    @Test
    void leavesAShortCleanDetailUntouched() {
        // Control for the truncation test: a short detail must not gain an ellipsis.
        assertEquals("missing required field 'tool_name'", sanitized("missing required field 'tool_name'"));
    }

    @Test
    void collapsesWhitespaceAndTrims() {
        assertEquals("line one line two", sanitized("  line one\nline two\t  "));
    }

    @Test
    void treatsAnAbsentOrBlankDetailAsAbsentRatherThanEmptyText() {
        assertTrue(noOpinionWithDetail(null).optionalDetail().isEmpty());
        assertTrue(noOpinionWithDetail("").optionalDetail().isEmpty());
        assertTrue(noOpinionWithDetail("   ").optionalDetail().isEmpty());
    }

    @Test
    void toStringReportsWhetherADetailExistsWithoutEverPrintingIt() {
        String token = "abcdefgh" + "12345678";
        DecisionResult.NoOpinion noOpinion = noOpinionWithDetail("Bearer " + token);

        String rendered = noOpinion.toString();

        assertTrue(rendered.contains("hasDetail=true"), rendered);
        assertFalse(rendered.contains(token), "the diagnostic payload must never reach toString");
        assertTrue(noOpinionWithDetail(null).toString().contains("hasDetail=false"));
    }

    // ---------------------------------------------------------------- Opinion construction

    @Test
    void opinionRejectsAnInvalidIdentity() {
        QuestionKey<Choice> key = QuestionKey.choice("intent");
        Map<QuestionKey<?>, DecisionAnswer> answers = Map.of(key, new Choice("yes"));

        assertEquals(
                "decisionId",
                assertThrows(
                                NullPointerException.class,
                                () -> new DecisionResult.Opinion(null, SCHEMA, answers, null, DecisionMetadata.empty()))
                        .getMessage());
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> new DecisionResult.Opinion("  ", SCHEMA, answers, null, DecisionMetadata.empty()))
                .getMessage()
                .contains("must not be blank"));
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> new DecisionResult.Opinion(DECISION_ID, 0, answers, null, DecisionMetadata.empty()))
                .getMessage()
                .contains("schemaVersion must be >= 1"));
        assertEquals(
                "answers",
                assertThrows(
                                NullPointerException.class,
                                () -> new DecisionResult.Opinion(
                                        DECISION_ID, SCHEMA, null, null, DecisionMetadata.empty()))
                        .getMessage());
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> new DecisionResult.Opinion(DECISION_ID, SCHEMA, Map.of(), null, DecisionMetadata.empty()))
                .getMessage()
                .contains("at least one answer"));
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> new DecisionResult.Opinion(DECISION_ID, SCHEMA, answers, "  ", DecisionMetadata.empty()))
                .getMessage()
                .contains("ruleId must not be blank if present"));
    }

    @Test
    void opinionDefaultsAbsentMetadataToEmpty() {
        DecisionResult.Opinion opinion = new DecisionResult.Opinion(
                DECISION_ID, SCHEMA, QuestionKey.choice("intent"), new Choice("yes"), null, null);

        assertEquals(DecisionMetadata.empty(), opinion.metadata());
        assertTrue(opinion.optionalRuleId().isEmpty());
    }

    @Test
    void opinionConvenienceConstructorsSupplyNullRuleIdAndEmptyMetadata() {
        Map<QuestionKey<?>, DecisionAnswer> answers = Map.of(QuestionKey.choice("intent"), new Choice("yes"));

        DecisionResult.Opinion threeArg = new DecisionResult.Opinion(DECISION_ID, SCHEMA, answers);
        assertNull(threeArg.ruleId());
        assertEquals(DecisionMetadata.empty(), threeArg.metadata());

        DecisionResult.Opinion fourArg = new DecisionResult.Opinion(DECISION_ID, SCHEMA, answers, "rule-9");
        assertEquals("rule-9", fourArg.ruleId());
        assertEquals("rule-9", fourArg.optionalRuleId().orElseThrow());
    }

    @Test
    void answerIsASynonymForGet() {
        QuestionKey<Choice> key = QuestionKey.choice("intent");
        DecisionResult.Opinion opinion = new DecisionResult.Opinion(
                DECISION_ID, SCHEMA, key, new Choice("yes"), "rule-1", DecisionMetadata.empty());

        assertSame(opinion.get(key), opinion.answer(key));
        assertEquals("yes", opinion.answer(key).label());
    }

    @Test
    void singleAnswerReturnsTheOnlyAnswerAndRefusesAmbiguity() {
        QuestionKey<Choice> first = QuestionKey.choice("intent");
        QuestionKey<Choice> second = QuestionKey.choice("secondary");
        DecisionResult.Opinion single = new DecisionResult.Opinion(
                DECISION_ID, SCHEMA, first, new Choice("yes"), "rule-1", DecisionMetadata.empty());
        assertEquals(
                "yes", assertInstanceOf(Choice.class, single.singleAnswer()).label());

        Map<QuestionKey<?>, DecisionAnswer> two = new LinkedHashMap<>();
        two.put(first, new Choice("yes"));
        two.put(second, new Choice("no"));
        DecisionResult.Opinion ambiguous =
                new DecisionResult.Opinion(DECISION_ID, SCHEMA, two, "rule-1", DecisionMetadata.empty());

        assertTrue(assertThrows(IllegalStateException.class, ambiguous::singleAnswer)
                .getMessage()
                .contains("Expected exactly 1 answer"));
    }

    // ---------------------------------------------------------------- narrowing

    @Test
    void asOpinionAndAsNoOpinionRoundTripAndRejectTheWrongVariant() {
        DecisionResult.Opinion opinion = new DecisionResult.Opinion(
                DECISION_ID, SCHEMA, QuestionKey.choice("intent"), new Choice("yes"), null, null);
        DecisionResult.NoOpinion noOpinion =
                new DecisionResult.NoOpinion(DECISION_ID, SCHEMA, ClassifierResult.NoOpinionReason.TIMEOUT);

        assertSame(opinion, opinion.asOpinion());
        assertSame(noOpinion, noOpinion.asNoOpinion());
        assertTrue(opinion.isOpinion());
        assertFalse(opinion.isNoOpinion());
        assertTrue(noOpinion.isNoOpinion());

        assertTrue(assertThrows(IllegalStateException.class, noOpinion::asOpinion)
                .getMessage()
                .contains("not an Opinion"));
        assertTrue(assertThrows(IllegalStateException.class, opinion::asNoOpinion)
                .getMessage()
                .contains("not a NoOpinion"));
    }

    // ---------------------------------------------------------------- NoOpinion construction

    @Test
    void noOpinionRejectsAnInvalidIdentity() {
        assertEquals(
                "decisionId",
                assertThrows(
                                NullPointerException.class,
                                () -> new DecisionResult.NoOpinion(
                                        null, SCHEMA, ClassifierResult.NoOpinionReason.TIMEOUT))
                        .getMessage());
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> new DecisionResult.NoOpinion(" ", SCHEMA, ClassifierResult.NoOpinionReason.TIMEOUT))
                .getMessage()
                .contains("must not be blank"));
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> new DecisionResult.NoOpinion(DECISION_ID, 0, ClassifierResult.NoOpinionReason.TIMEOUT))
                .getMessage()
                .contains("schemaVersion must be >= 1"));
        assertEquals(
                "reason",
                assertThrows(NullPointerException.class, () -> new DecisionResult.NoOpinion(DECISION_ID, SCHEMA, null))
                        .getMessage());
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> new DecisionResult.NoOpinion(
                                DECISION_ID, SCHEMA, ClassifierResult.NoOpinionReason.TIMEOUT, "  "))
                .getMessage()
                .contains("ruleId must not be blank if present"));
    }

    @Test
    void noOpinionConvenienceConstructorsSupplyNullRuleId() {
        DecisionResult.NoOpinion reasonOnly = new DecisionResult.NoOpinion(ClassifierResult.NoOpinionReason.DISABLED);
        assertEquals("unspecified", reasonOnly.decisionId());
        assertEquals(1, reasonOnly.schemaVersion());
        assertNull(reasonOnly.ruleId());

        DecisionResult.NoOpinion withId =
                new DecisionResult.NoOpinion(DECISION_ID, SCHEMA, ClassifierResult.NoOpinionReason.MISSING_MODEL);
        assertNull(withId.ruleId());
        assertTrue(withId.optionalDetail().isEmpty());

        DecisionResult.NoOpinion withRule = new DecisionResult.NoOpinion(
                DECISION_ID, SCHEMA, ClassifierResult.NoOpinionReason.OVERLOAD_REJECTED, "rule-3");
        assertEquals("rule-3", withRule.ruleId());
        assertTrue(withRule.optionalDetail().isEmpty());
    }

    @Test
    void ofMalformedResponseMapsToInferenceErrorAndKeepsTheRuleId() {
        DecisionResult.NoOpinion noOpinion =
                DecisionResult.NoOpinion.ofMalformedResponse(DECISION_ID, SCHEMA, "rule-7", "bad envelope");

        assertEquals(ClassifierResult.NoOpinionReason.INFERENCE_ERROR, noOpinion.reason());
        assertEquals("rule-7", noOpinion.ruleId());
        assertEquals("bad envelope", noOpinion.optionalDetail().orElseThrow());
    }

    // ---------------------------------------------------------------- OpinionBuilder

    @Test
    void opinionBuilderRejectsAnInvalidIdentity() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DecisionResult.opinionBuilder("  ", SCHEMA))
                .getMessage()
                .contains("must not be blank"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DecisionResult.opinionBuilder(DECISION_ID, 0))
                .getMessage()
                .contains("schemaVersion must be >= 1"));
        assertEquals(
                "decisionId",
                assertThrows(NullPointerException.class, () -> DecisionResult.opinionBuilder(null, SCHEMA))
                        .getMessage());
    }

    @Test
    void opinionBuilderRejectsASecondAnswerForTheSameHandleName() {
        // Two distinct key instances with the same name are the realistic duplicate: identity differs, so a
        // Map would keep both, and the correlation contract would silently hold two answers for one handle.
        DecisionResult.OpinionBuilder builder =
                DecisionResult.opinionBuilder(DECISION_ID, SCHEMA).put(QuestionKey.choice("intent"), new Choice("yes"));

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> builder.put(QuestionKey.choice("intent"), new Choice("no")));
        assertTrue(thrown.getMessage().contains("Duplicate answer for handle"), thrown.getMessage());
    }

    @Test
    void opinionBuilderAssemblesRuleIdAndMetadata() {
        DecisionResult.Opinion opinion = DecisionResult.opinionBuilder(DECISION_ID, SCHEMA)
                .put(QuestionKey.choice("intent"), new Choice("yes", 0.75))
                .ruleId("rule-5")
                .metadata(DecisionMetadata.empty())
                .build();

        assertEquals("rule-5", opinion.ruleId());
        assertEquals(1, opinion.answers().size());
        assertEquals(
                0.75, opinion.get(QuestionKey.choice("intent")).confidence().getAsDouble(), 1e-9);
    }

    // ---------------------------------------------------------------- legacy bridges

    @Test
    void fromClassifierResultMapsAnOpinionOntoAChoiceAnswer() {
        QuestionKey<Choice> key = QuestionKey.choice("intent");

        DecisionResult mapped = DecisionResult.fromClassifierResult(
                DECISION_ID, SCHEMA, key, new ClassifierResult.Opinion<>("route_to_research", 0.9f, "rule-2"));

        DecisionResult.Opinion opinion = assertInstanceOf(DecisionResult.Opinion.class, mapped);
        Choice answer = opinion.get(key);
        assertEquals("route_to_research", answer.label());
        assertEquals(0.9f, (float) answer.confidence().getAsDouble(), 1e-6f);
        assertEquals("rule-2", opinion.ruleId());
    }

    @Test
    void fromClassifierResultPreservesAbsenceRatherThanInventingAnOpinion() {
        DecisionResult mapped = DecisionResult.fromClassifierResult(
                DECISION_ID,
                SCHEMA,
                QuestionKey.choice("intent"),
                new ClassifierResult.NoOpinion<String>(ClassifierResult.NoOpinionReason.MISSING_MODEL));

        DecisionResult.NoOpinion noOpinion = assertInstanceOf(DecisionResult.NoOpinion.class, mapped);
        assertEquals(ClassifierResult.NoOpinionReason.MISSING_MODEL, noOpinion.reason());
    }

    @Test
    void fromClassifierResultRejectsNullArguments() {
        QuestionKey<Choice> key = QuestionKey.choice("intent");
        ClassifierResult<String> legacy = new ClassifierResult.Opinion<>("yes", 0.5f, "rule-1");

        assertThrows(NullPointerException.class, () -> DecisionResult.fromClassifierResult(null, SCHEMA, key, legacy));
        assertThrows(
                NullPointerException.class,
                () -> DecisionResult.fromClassifierResult(DECISION_ID, SCHEMA, null, legacy));
        assertThrows(
                NullPointerException.class, () -> DecisionResult.fromClassifierResult(DECISION_ID, SCHEMA, key, null));
    }

    @Test
    void toClassifierResultCarriesLabelConfidenceAndRule() {
        QuestionKey<Choice> key = QuestionKey.choice("intent");
        DecisionResult.Opinion opinion = new DecisionResult.Opinion(
                DECISION_ID, SCHEMA, key, new Choice("research", 0.8), "rule-4", DecisionMetadata.empty());

        ClassifierResult.Opinion<?> legacy = asLegacyOpinion(opinion.toClassifierResult(key));

        assertEquals("research", legacy.value());
        assertEquals(0.8f, legacy.confidence(), 1e-6f);
        assertEquals("rule-4", legacy.ruleId());
    }

    @Test
    void toClassifierResultReportsLowConfidenceRatherThanFabricatingCertainty() {
        // The documented behaviour: an absent confidence must not become a synthetic 1.0.
        QuestionKey<Choice> key = QuestionKey.choice("intent");
        DecisionResult.Opinion opinion = new DecisionResult.Opinion(
                DECISION_ID,
                SCHEMA,
                key,
                new Choice("research", OptionalDouble.empty()),
                "rule-4",
                DecisionMetadata.empty());

        ClassifierResult.NoOpinion<?> legacy = asLegacyNoOpinion(opinion.toClassifierResult(key));

        assertEquals(ClassifierResult.NoOpinionReason.LOW_CONFIDENCE, legacy.reason());
    }

    @Test
    void toClassifierResultReportsInferenceErrorWhenTheRuleIsMissing() {
        QuestionKey<Choice> key = QuestionKey.choice("intent");
        DecisionResult.Opinion opinion = new DecisionResult.Opinion(
                DECISION_ID, SCHEMA, key, new Choice("research", 0.8), null, DecisionMetadata.empty());

        ClassifierResult.NoOpinion<?> legacy = asLegacyNoOpinion(opinion.toClassifierResult(key));

        assertEquals(ClassifierResult.NoOpinionReason.INFERENCE_ERROR, legacy.reason());
    }

    @Test
    void toClassifierResultPassesANoOpinionThroughUnchanged() {
        DecisionResult.NoOpinion noOpinion =
                new DecisionResult.NoOpinion(DECISION_ID, SCHEMA, ClassifierResult.NoOpinionReason.CIRCUIT_OPEN);

        ClassifierResult.NoOpinion<?> legacy =
                asLegacyNoOpinion(noOpinion.toClassifierResult(QuestionKey.choice("intent")));

        assertEquals(ClassifierResult.NoOpinionReason.CIRCUIT_OPEN, legacy.reason());
    }

    private static ClassifierResult.Opinion<?> asLegacyOpinion(ClassifierResult<String> result) {
        return result instanceof ClassifierResult.Opinion<?> opinion ? opinion : fail("expected a legacy Opinion");
    }

    private static ClassifierResult.NoOpinion<?> asLegacyNoOpinion(ClassifierResult<String> result) {
        return result instanceof ClassifierResult.NoOpinion<?> noOpinion
                ? noOpinion
                : fail("expected a legacy NoOpinion");
    }

    @Test
    void optionalRuleIdReflectsAbsenceWithoutInventingAValue() {
        DecisionResult withRule =
                new DecisionResult.NoOpinion(DECISION_ID, SCHEMA, ClassifierResult.NoOpinionReason.TIMEOUT, "rule-8");
        DecisionResult withoutRule =
                new DecisionResult.NoOpinion(DECISION_ID, SCHEMA, ClassifierResult.NoOpinionReason.TIMEOUT);

        assertEquals(Optional.of("rule-8"), withRule.optionalRuleId());
        assertEquals(Optional.empty(), withoutRule.optionalRuleId());
    }

    @Test
    void answerMapsAreDefensivelyCopiedAndUnmodifiable() {
        // The constructor copies into a LinkedHashMap and wraps it unmodifiable, so a caller that mutates its
        // own map afterwards must not change the result, and no consumer may add or remove answers.
        QuestionKey<Choice> key = QuestionKey.choice("intent");
        Map<QuestionKey<?>, DecisionAnswer> mutable = new LinkedHashMap<>();
        mutable.put(key, new Choice("yes"));

        DecisionResult.Opinion opinion =
                new DecisionResult.Opinion(DECISION_ID, SCHEMA, mutable, null, DecisionMetadata.empty());
        mutable.clear();

        assertEquals(Set.of(key), opinion.answers().keySet(), "the opinion must not follow the caller's map");
        assertThrows(
                UnsupportedOperationException.class, () -> opinion.answers().clear());
    }
}
