package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link DecisionPayloadDecoder}.
 *
 * <p>The decoder is a pure static translator from a raw provider payload map to a typed
 * {@link DecisionResult}, so every arm is reachable by handing it a hand-built map — no container, no
 * provider, no mock. The class documents a strict fail-closed contract, and these tests assert that
 * contract at each point it can be violated: a malformed envelope field, an unknown handle, a
 * wrong-primitive answer, a probability or legend that leaves the declared universe, and the legacy
 * single-value shape.
 *
 * <p>Six arms are unreachable by construction and are deliberately left dark (see
 * {@code PINNED-FINDINGS.md}); none is a behavioural hole, each is a guard against a state the type system
 * already excludes:
 * <ul>
 *   <li>the {@code catch} around {@code builder.build()} (lines 145-150) — every precondition the
 *       {@link DecisionResult.Opinion} constructor enforces is enforced upstream, so an assembly this method
 *       produces cannot be rejected;</li>
 *   <li>the {@code default} arm of {@code decodeAnswer}'s primitive switch (lines 217/228) —
 *       {@link DecisionPrimitive} has exactly the three handled constants;</li>
 *   <li>the {@code catch} around {@code new Noul(prob)} (lines 407-408) — the range check on the line above
 *       is the same one {@link Noul}'s constructor applies;</li>
 *   <li>{@code question.options().isEmpty()} in {@code decodeChoice} (line 279) and in
 *       {@code probabilitiesWithinOptions} (line 361) — a {@link DecisionQuestion} whose key is
 *       {@code CHOICE} is required to declare at least two options, so the unrestricted-option-set arm is
 *       unreachable through this decoder.</li>
 * </ul>
 */
class DecisionPayloadDecoderTest {

    private static final String DECISION_ID = "dec-payload-decoder-test";
    private static final int SCHEMA_VERSION = 3;
    private static final String HANDLE = "h";
    private static final List<String> TWO_OPTIONS = List.of("A", "B");
    private static final List<String> TWO_LEVELS = List.of("low", "high");

    private static DecisionRequest choiceRequest() {
        return requestOf(b -> b.registerChoice(HANDLE, "criteria", TWO_OPTIONS));
    }

    private static DecisionRequest scoreRequest() {
        return requestOf(b -> b.registerScore(HANDLE, "criteria", TWO_LEVELS));
    }

    private static DecisionRequest noulRequest() {
        return requestOf(b -> b.registerNoul(HANDLE, "criteria"));
    }

    private static DecisionRequest requestOf(Consumer<DecisionRequest.Builder> configure) {
        DecisionRequest.Builder builder = DecisionRequest.builder(DECISION_ID, SCHEMA_VERSION, "bounded state");
        configure.accept(builder);
        return builder.build();
    }

    /** Insertion-ordered map that, unlike {@code Map.of}, tolerates null values. */
    private static Map<String, Object> payload(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> answers(Map<String, Object> handleToAnswer) {
        return payload("answers", handleToAnswer);
    }

    /** A single-question payload whose only answer is {@code rawAnswer}. */
    private static Map<String, Object> answer(Object rawAnswer) {
        return answers(payload(HANDLE, rawAnswer));
    }

    private static DecisionAnswer singleAnswer(DecisionResult result) {
        assertTrue(result.isOpinion(), () -> "expected an Opinion but was " + result);
        return result.asOpinion().singleAnswer();
    }

    private static void assertInferenceError(DecisionResult result) {
        assertTrue(result.isNoOpinion(), () -> "expected a fail-closed NoOpinion but was " + result);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                result.asNoOpinion().reason());
    }

    // ---- envelope ----

    @Test
    void decodeRejectsANullRequest() {
        assertThrows(IllegalArgumentException.class, () -> DecisionPayloadDecoder.decode(null, Map.of()));
    }

    @Test
    void decodeReportsNoOpinionForANullPayload() {
        DecisionResult result = DecisionPayloadDecoder.decode(choiceRequest(), null);

        assertInferenceError(result);
        assertEquals(
                "Provider payload is null",
                result.asNoOpinion().optionalDetail().orElseThrow());
    }

    @Test
    void decodeRejectsAMalformedRuleId() {
        // A non-string value and a blank string are both "the key is there but unusable".
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("ruleId", 42)));
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("ruleId", "   ")));
    }

    // ---- metadata envelope ----

    @Test
    void decodeRejectsAMalformedProviderId() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("providerId", 7, HANDLE, "A")));
    }

    @Test
    void decodeRejectsAMalformedModelId() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("modelId", 7, HANDLE, "A")));
    }

    @Test
    void decodeRejectsAMalformedModelWireKey() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("model", 7, HANDLE, "A")));
    }

    @Test
    void decodeRejectsAMalformedLatency() {
        // Absent-vs-malformed matters: a non-numeric latency is a malformed envelope, not a missing field.
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("latencyMs", "x", HANDLE, "A")));
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("latencyMs", -1, HANDLE, "A")));
    }

    @Test
    void decodeRejectsAMalformedInputTokenCount() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("inputTokens", "x", HANDLE, "A")));
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("inputTokens", -5, HANDLE, "A")));
    }

    @Test
    void decodeRejectsAMalformedOutputTokenCount() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("outputTokens", "x", HANDLE, "A")));
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("outputTokens", -5, HANDLE, "A")));
    }

    @Test
    void decodeReadsTheWholeMetadataEnvelope() {
        DecisionResult result = DecisionPayloadDecoder.decode(
                choiceRequest(),
                payload(
                        "providerId",
                        "typesafe",
                        "modelId",
                        "jev-1.13.0",
                        "latencyMs",
                        1500,
                        "inputTokens",
                        10,
                        "outputTokens",
                        20,
                        HANDLE,
                        "A"));

        DecisionMetadata metadata = result.asOpinion().metadata();
        assertEquals("typesafe", metadata.providerId());
        assertEquals("jev-1.13.0", metadata.modelId());
        assertEquals(1500L, metadata.latency().toMillis());
        assertEquals(10L, metadata.inputTokens());
        assertEquals(20L, metadata.outputTokens());
    }

    @Test
    void decodeReadsTokenCountsFromTheNestedUsageObject() {
        DecisionResult result = DecisionPayloadDecoder.decode(
                choiceRequest(), payload("usage", Map.of("input_tokens", 3, "output_tokens", 4), HANDLE, "A"));

        DecisionMetadata metadata = result.asOpinion().metadata();
        assertEquals(3L, metadata.inputTokens());
        assertEquals(4L, metadata.outputTokens());
    }

    @Test
    void decodeReadsOpenAiShapedTokenCountsFromTheNestedUsageObject() {
        // A local runtime (LM Studio / Ollama / the `omlx` slot) reports usage in the OpenAI shape.
        // This used to be read as "no token data at all": isTokenReported returned false, nothing
        // failed, and DecisionMetadata carried nulls — losing cost telemetry silently rather than
        // visibly. Both spellings must land in the metadata.
        DecisionResult result = DecisionPayloadDecoder.decode(
                choiceRequest(), payload("usage", Map.of("prompt_tokens", 30, "completion_tokens", 9), HANDLE, "A"));

        DecisionMetadata metadata = result.asOpinion().metadata();
        assertEquals(30L, metadata.inputTokens());
        assertEquals(9L, metadata.outputTokens());
    }

    @Test
    void decodeStillFailsClosedOnAMalformedOpenAiShapedTokenCount() {
        // Widening which names are read must not widen what counts as valid: a present-but-unparseable
        // value stays a malformed response rather than becoming a zero or an absent count.
        assertInferenceError(DecisionPayloadDecoder.decode(
                choiceRequest(), payload("usage", Map.of("prompt_tokens", "lots"), HANDLE, "A")));
        assertInferenceError(DecisionPayloadDecoder.decode(
                choiceRequest(), payload("usage", Map.of("completion_tokens", -1), HANDLE, "A")));
    }

    @Test
    void decodeAcceptsTheSystemOneModelWireKey() {
        DecisionResult result =
                DecisionPayloadDecoder.decode(choiceRequest(), payload("model", "jev-1.13.0", HANDLE, "A"));

        assertEquals("jev-1.13.0", result.asOpinion().metadata().modelId());
    }

    // ---- flat (non-nested) payloads ----

    @Test
    void decodeAcceptsAFlatPayloadWithKnownEnvelopeKeys() {
        DecisionResult result = DecisionPayloadDecoder.decode(choiceRequest(), payload("ruleId", "r1", HANDLE, "A"));

        assertEquals("r1", result.ruleId());
        assertInstanceOf(Choice.class, singleAnswer(result));
    }

    @Test
    void decodeRejectsAnUnknownFlatKey() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("notAHandle", "x", HANDLE, "A")));
    }

    @Test
    void decodeRejectsANullAnswerValue() {
        // The handle is present, so the payload is not partial — but its value is unusable.
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), answer(null)));
    }

    // ---- the legacy single-value shape is gated on three conditions ----

    @Test
    void decodeSkipsTheLegacyPathWhenAnAnswersContainerIsAlsoPresent() {
        // "value" present but "answers" wins: the nested shape is authoritative.
        DecisionResult result =
                DecisionPayloadDecoder.decode(choiceRequest(), payload("value", "A", "answers", payload(HANDLE, "A")));

        assertEquals("A", assertInstanceOf(Choice.class, singleAnswer(result)).label());
    }

    @Test
    void decodeSkipsTheLegacyPathForAMultiQuestionRequest() {
        DecisionRequest twoQuestions = requestOf(b -> {
            b.registerChoice("h", "criteria", TWO_OPTIONS);
            b.registerChoice("h2", "criteria", TWO_OPTIONS);
        });

        // "value" is not a registered handle, so a flat multi-question payload fails closed rather than
        // silently decoding only the first question from the legacy field.
        assertInferenceError(DecisionPayloadDecoder.decode(twoQuestions, payload("value", "A", "h", "A", "h2", "A")));
    }

    @Test
    void decodeSkipsTheLegacyPathWhenTheSingleQuestionIsNotAChoice() {
        assertInferenceError(DecisionPayloadDecoder.decode(scoreRequest(), payload("value", 0.5)));
    }

    // ---- choice answers ----

    @Test
    void decodeReadsAChoiceConfidence() {
        DecisionResult result =
                DecisionPayloadDecoder.decode(choiceRequest(), answer(payload("choice", "A", "confidence", 0.8)));

        Choice choice = assertInstanceOf(Choice.class, singleAnswer(result));
        assertEquals("A", choice.label());
        assertEquals(0.8, choice.confidence().orElseThrow());
    }

    @Test
    void decodeRejectsAChoiceConfidenceThatIsNotNumericOrInRange() {
        assertInferenceError(
                DecisionPayloadDecoder.decode(choiceRequest(), answer(payload("choice", "A", "confidence", "x"))));
        assertInferenceError(
                DecisionPayloadDecoder.decode(choiceRequest(), answer(payload("choice", "A", "confidence", -0.5))));
        assertInferenceError(
                DecisionPayloadDecoder.decode(choiceRequest(), answer(payload("choice", "A", "confidence", 1.5))));
    }

    @Test
    void decodeRejectsABlankProbabilityKey() {
        assertInferenceError(DecisionPayloadDecoder.decode(
                choiceRequest(), answer(payload("choice", "A", "probabilities", payload("", 0.5)))));
    }

    @Test
    void decodeRejectsANonStringProbabilityKey() {
        assertInferenceError(DecisionPayloadDecoder.decode(
                choiceRequest(), answer(payload("choice", "A", "probabilities", Map.of(1, 0.5)))));
    }

    @Test
    void decodeRejectsAProbabilityThatIsNotNumericOrInRange() {
        assertInferenceError(DecisionPayloadDecoder.decode(
                choiceRequest(), answer(payload("choice", "A", "probabilities", payload("A", "x")))));
        assertInferenceError(DecisionPayloadDecoder.decode(
                choiceRequest(), answer(payload("choice", "A", "probabilities", payload("A", -0.5)))));
        assertInferenceError(DecisionPayloadDecoder.decode(
                choiceRequest(), answer(payload("choice", "A", "probabilities", payload("A", 1.5)))));
    }

    @Test
    void decodeRejectsAChoiceWithNoLabel() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), answer(payload("confidence", 0.5))));
    }

    @Test
    void decodeRejectsABlankChoiceLabel() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), answer(payload("choice", "   "))));
    }

    @Test
    void decodeRejectsANonMapChoiceAnswer() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), answer(true)));
    }

    @Test
    void decodeRejectsAChoiceWhoseLabelIsMissingFromItsOwnDistribution() {
        // Both keys are inside the declared option set, so the option-universe check passes; the answer is
        // still incoherent because the selected label is absent from the distribution it reports.
        assertInferenceError(DecisionPayloadDecoder.decode(
                choiceRequest(), answer(payload("choice", "B", "probabilities", payload("A", 0.5)))));
    }

    @Test
    void decodeAcceptsAChoiceWithAValidDistribution() {
        DecisionResult result = DecisionPayloadDecoder.decode(
                choiceRequest(), answer(payload("choice", "A", "probabilities", payload("A", 0.5, "B", 0.5))));

        Choice choice = assertInstanceOf(Choice.class, singleAnswer(result));
        assertEquals("A", choice.label());
        assertEquals(0.5, choice.probability("B").orElseThrow());
    }

    // ---- score answers ----

    @Test
    void decodeRejectsAStringScoreAnswer() {
        assertInferenceError(DecisionPayloadDecoder.decode(scoreRequest(), answer("0.5")));
    }

    @Test
    void decodeRejectsANonNumericScore() {
        assertInferenceError(DecisionPayloadDecoder.decode(scoreRequest(), answer(payload("score", "x"))));
    }

    @Test
    void decodeRejectsAScoreAnswerWithoutAScoreField() {
        assertInferenceError(DecisionPayloadDecoder.decode(scoreRequest(), answer(payload("confidence", 0.5))));
    }

    @Test
    void decodeRejectsANonMapScoreAnswer() {
        assertInferenceError(DecisionPayloadDecoder.decode(scoreRequest(), answer(true)));
    }

    @Test
    void decodeRejectsAScoreConfidenceThatIsNotNumericOrInRange() {
        assertInferenceError(
                DecisionPayloadDecoder.decode(scoreRequest(), answer(payload("score", 0.5, "confidence", "x"))));
        assertInferenceError(
                DecisionPayloadDecoder.decode(scoreRequest(), answer(payload("score", 0.5, "confidence", -0.5))));
        assertInferenceError(
                DecisionPayloadDecoder.decode(scoreRequest(), answer(payload("score", 0.5, "confidence", 2.0))));
    }

    @Test
    void decodeRejectsAMalformedScoreProbabilityCollection() {
        assertInferenceError(
                DecisionPayloadDecoder.decode(scoreRequest(), answer(payload("score", 0.5, "probabilities", "nope"))));
    }

    @Test
    void decodeRejectsANonFiniteScore() {
        assertInferenceError(DecisionPayloadDecoder.decode(scoreRequest(), answer(Double.NaN)));
    }

    @Test
    void decodeRejectsASingleLevelProbabilityDistribution() {
        // parseProbabilities accepts a one-entry distribution, but Score itself requires at least two
        // levels — the decoder must fail closed rather than let the answer type's own guard escape.
        assertInferenceError(DecisionPayloadDecoder.decode(
                scoreRequest(), answer(payload("score", 0.0, "probabilities", List.of(0.5)))));
    }

    @Test
    void decodeAcceptsAScoreAgainstAnEmptyRubric() {
        DecisionResult result = DecisionPayloadDecoder.decode(
                requestOf(b -> b.registerScore(HANDLE, "criteria", List.of())), answer(0.5));

        Score score = assertInstanceOf(Score.class, singleAnswer(result));
        assertEquals(0.5, score.score());
        assertTrue(score.legend().isEmpty());
    }

    @Test
    void decodeAcceptsAProviderSuppliedLegendAgainstAnEmptyRubric() {
        // With no declared rubric the legend is unrestricted, so a provider legend is carried through.
        DecisionResult result = DecisionPayloadDecoder.decode(
                requestOf(b -> b.registerScore(HANDLE, "criteria", List.of())),
                answer(payload("score", 0.5, "legend", TWO_LEVELS)));

        assertEquals(
                TWO_LEVELS, assertInstanceOf(Score.class, singleAnswer(result)).legend());
    }

    // ---- noul answers ----

    @Test
    void decodeRejectsAStringNoulAnswer() {
        assertInferenceError(DecisionPayloadDecoder.decode(noulRequest(), answer("0.5")));
    }

    @Test
    void decodeRejectsANonMapNoulAnswer() {
        assertInferenceError(DecisionPayloadDecoder.decode(noulRequest(), answer(true)));
    }

    @Test
    void decodeReadsTheSystemOneNoulKey() {
        DecisionResult result = DecisionPayloadDecoder.decode(noulRequest(), answer(payload("noul", 0.7)));

        assertEquals(0.7, assertInstanceOf(Noul.class, singleAnswer(result)).probability());
    }

    @Test
    void decodeReadsTheLegacyProbabilityKey() {
        DecisionResult result = DecisionPayloadDecoder.decode(noulRequest(), answer(payload("probability", 0.7)));

        assertEquals(0.7, assertInstanceOf(Noul.class, singleAnswer(result)).probability());
    }

    @Test
    void decodeReadsTheLegacyValueKey() {
        DecisionResult result = DecisionPayloadDecoder.decode(noulRequest(), answer(payload("value", 0.7)));

        assertEquals(0.7, assertInstanceOf(Noul.class, singleAnswer(result)).probability());
    }

    @Test
    void decodeRejectsANoulObjectWithNoRecognisedKey() {
        assertInferenceError(DecisionPayloadDecoder.decode(noulRequest(), answer(payload())));
    }

    @Test
    void decodeRejectsANoulCarryingAChoiceOrScoreKey() {
        // A noul is a proposition probability; an object shaped like a choice or score answer is rejected
        // rather than reinterpreted.
        assertInferenceError(DecisionPayloadDecoder.decode(noulRequest(), answer(payload("choice", "A"))));
        assertInferenceError(DecisionPayloadDecoder.decode(noulRequest(), answer(payload("score", 0.5))));
    }

    @Test
    void decodeRejectsANoulProbabilityThatIsMissingOrOutOfRange() {
        assertInferenceError(DecisionPayloadDecoder.decode(noulRequest(), answer(payload("noul", "x"))));
        assertInferenceError(DecisionPayloadDecoder.decode(noulRequest(), answer(payload("noul", -0.5))));
        assertInferenceError(DecisionPayloadDecoder.decode(noulRequest(), answer(payload("noul", 1.5))));
        assertInferenceError(DecisionPayloadDecoder.decode(noulRequest(), answer(Double.NaN)));
    }

    // ---- legacy single-value payload ----

    @Test
    void decodeRejectsALegacyPayloadWithANullValue() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("value", null)));
    }

    @Test
    void decodeRejectsALegacyLabelOutsideTheDeclaredOptions() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("value", "Z")));
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("value", "   ")));
    }

    @Test
    void decodeRejectsALegacyConfidenceThatIsNotNumericOrInRange() {
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("value", "A", "confidence", "x")));
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("value", "A", "confidence", -0.5)));
        assertInferenceError(DecisionPayloadDecoder.decode(choiceRequest(), payload("value", "A", "confidence", 1.5)));
    }

    @Test
    void decodeAcceptsALegacyPayloadWithAndWithoutConfidence() {
        DecisionResult bare = DecisionPayloadDecoder.decode(choiceRequest(), payload("value", "A"));
        Choice bareChoice = assertInstanceOf(Choice.class, singleAnswer(bare));
        assertEquals("A", bareChoice.label());
        assertTrue(bareChoice.confidence().isEmpty(), "an absent legacy confidence must stay absent");
        assertTrue(bareChoice.probabilities().isEmpty());

        DecisionResult scored =
                DecisionPayloadDecoder.decode(choiceRequest(), payload("value", "A", "confidence", 0.9));
        assertEquals(
                0.9,
                assertInstanceOf(Choice.class, singleAnswer(scored))
                        .confidence()
                        .orElseThrow());
    }
}
