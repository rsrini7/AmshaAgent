package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

/**
 * Unit and contract tests for DecisionResult (Opinion, NoOpinion), exact QuestionKey identity,
 * DecisionPayloadDecoder, absent-field preservation, and legacy bridging.
 */
class DecisionPlaneEvaluationTest {

    @Test
    void validOpinionConstructionWithBoundKeys() {
        var reqBuilder = DecisionRequest.builder("intent.route", 1, "state");
        QuestionKey<Choice> choiceKey = reqBuilder.addChoice("role", "Select role", List.of("coder", "researcher"));
        QuestionKey<Noul> noulKey = reqBuilder.addNoul("is_urgent", "Is request urgent?");
        reqBuilder.build();

        var choiceAnswer = new Choice("coder", OptionalDouble.of(0.95));
        var noulAnswer = new Noul(0.8);

        var opinion = DecisionResult.Opinion.opinionBuilder("intent.route", 1)
                .put(choiceKey, choiceAnswer)
                .put(noulKey, noulAnswer)
                .ruleId("rule-123")
                .metadata(new DecisionMetadata("onnx-local", "m1", Duration.ofMillis(12), 100L, 20L))
                .build();

        assertEquals("intent.route", opinion.decisionId());
        assertEquals(1, opinion.schemaVersion());
        assertEquals("rule-123", opinion.ruleId());
        assertTrue(opinion.isOpinion());
        assertFalse(opinion.isNoOpinion());

        assertEquals("coder", opinion.get(choiceKey).label());
        assertEquals(0.8, opinion.get(noulKey).probability(), 1e-6);

        assertNotNull(opinion.metadata());
        assertEquals("onnx-local", opinion.metadata().providerId());
        assertEquals(Duration.ofMillis(12), opinion.metadata().latency());

        var singleOp = new DecisionResult.Opinion(
                "single.decision", 1, choiceKey, choiceAnswer, "rule-1", DecisionMetadata.empty());
        assertEquals("coder", ((Choice) singleOp.singleAnswer()).label());
    }

    @Test
    void exactQuestionKeyIdentityEnforcedOnLookup() {
        var reqBuilder1 = DecisionRequest.builder("d1", 1, "state1");
        QuestionKey<Choice> boundKey1 = reqBuilder1.addChoice("intent", "crit", List.of("read", "write"));
        reqBuilder1.build();

        var reqBuilder2 = DecisionRequest.builder("d2", 1, "state2");
        QuestionKey<Choice> boundKey2 = reqBuilder2.addChoice("intent", "crit", List.of("read", "write"));
        reqBuilder2.build();

        QuestionKey<Choice> unboundKey = QuestionKey.choice("intent");

        var opinion = DecisionResult.Opinion.opinionBuilder("d1", 1)
                .put(boundKey1, new Choice("read"))
                .build();

        // Exact bound key succeeds
        assertEquals("read", opinion.get(boundKey1).label());

        // Same-named key from another request (different owner) fails closed
        var exForeign = assertThrows(IllegalArgumentException.class, () -> opinion.get(boundKey2));
        assertTrue(exForeign.getMessage().contains("Unknown question handle"));

        // Same-named unbound key (null owner) fails closed
        var exUnbound = assertThrows(IllegalArgumentException.class, () -> opinion.get(unboundKey));
        assertTrue(exUnbound.getMessage().contains("Unknown question handle"));
    }

    @Test
    void validNoOpinionConstructionAndPayloadFreeToString() {
        for (ClassifierResult.NoOpinionReason reason : ClassifierResult.NoOpinionReason.values()) {
            var noOp = new DecisionResult.NoOpinion("test.decision", 1, reason, "rule-x", "sample detail");
            assertEquals("test.decision", noOp.decisionId());
            assertEquals(1, noOp.schemaVersion());
            assertEquals(reason, noOp.reason());
            assertEquals("rule-x", noOp.ruleId());
            assertEquals("sample detail", noOp.detail());
            assertTrue(noOp.isNoOpinion());

            String str = noOp.toString();
            assertTrue(str.contains("test.decision"));
            assertTrue(str.contains("hasDetail=true"));
            assertFalse(str.contains("sample detail"), "toString must not expose raw detail");
        }

        var malformed = DecisionResult.NoOpinion.ofMalformedResponse("d1", 2, "r1", "missing field");
        assertEquals(ClassifierResult.NoOpinionReason.INFERENCE_ERROR, malformed.reason());
        assertEquals("missing field", malformed.detail());
    }

    @Test
    void duplicateQuestionHandlesFailClosed() {
        var builder = DecisionRequest.builder("dup.test", 1, "state");
        builder.addChoice("intent", "choose intent", List.of("read", "write"));

        assertThrows(
                IllegalArgumentException.class,
                () -> builder.addChoice("intent", "duplicate handle", List.of("opt1", "opt2")));

        var opBuilder = DecisionResult.Opinion.opinionBuilder("dup.op", 1);
        var key = QuestionKey.choice("intent");
        opBuilder.put(key, new Choice("read"));
        assertThrows(IllegalArgumentException.class, () -> opBuilder.put(key, new Choice("write")));
    }

    @Test
    void unknownQuestionHandlesFailClosed() {
        var knownKey = QuestionKey.choice("known");
        var unknownKey = QuestionKey.choice("unknown");

        var opinion = DecisionResult.Opinion.opinionBuilder("test.decision", 1)
                .put(knownKey, new Choice("val"))
                .build();

        assertEquals("val", opinion.get(knownKey).label());
        var ex = assertThrows(IllegalArgumentException.class, () -> opinion.get(unknownKey));
        assertTrue(ex.getMessage().contains("Unknown question handle"));
    }

    @Test
    void wrongPrimitiveTypeCorrelationFailsClosed() {
        var choiceKey = QuestionKey.choice("item");
        var noulKeyForSameName = QuestionKey.noul("item");

        var opinion = DecisionResult.Opinion.opinionBuilder("test.decision", 1)
                .put(choiceKey, new Choice("coder"))
                .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> opinion.get(noulKeyForSameName));
        assertTrue(ex.getMessage().contains("Unknown question handle"));

        var exCast = assertThrows(IllegalArgumentException.class, () -> noulKeyForSameName.cast(new Choice("coder")));
        assertTrue(exCast.getMessage().contains("expected Noul"));
    }

    @Test
    void missingOptionalConfidenceProbabilityMetadataPreserved() {
        var builder = DecisionRequest.builder("d1", 1, "test-state");
        QuestionKey<Choice> boundChoiceKey = builder.addChoice("q1", "crit1", List.of("A", "B"));
        QuestionKey<Score> boundScoreKey = builder.addScore("q2", "crit2", List.of("L0", "L1"));
        var request = builder.build();

        Map<String, Object> raw = Map.of("answers", Map.of("q1", "A", "q2", 0.5));

        DecisionResult result = DecisionPayloadDecoder.decode(request, raw);
        assertTrue(result.isOpinion());
        DecisionResult.Opinion opinion = result.asOpinion();

        Choice choice = opinion.get(boundChoiceKey);
        assertTrue(choice.confidence().isEmpty(), "confidence must remain absent");
        assertTrue(choice.probabilities().isEmpty(), "probabilities must remain absent");

        Score score = opinion.get(boundScoreKey);
        assertTrue(score.confidence().isEmpty(), "confidence must remain absent");
        assertTrue(score.probabilities().isEmpty(), "probabilities must remain absent");
        assertTrue(score.mostLikelyLevel().isEmpty(), "mostLikelyLevel must remain absent");

        assertNull(opinion.metadata().latency());
        assertNull(opinion.metadata().inputTokens());
        assertNull(opinion.ruleId());
    }

    @Test
    void numericStringsAreRejectedForConfidenceFields() {
        QuestionKey<Choice> choiceKey = QuestionKey.choice("c");
        DecisionRequest request = DecisionRequest.builder("numeric.string", 1, "state")
                .addQuestion(DecisionQuestion.choice(choiceKey, "choose", List.of("a", "b")))
                .build();

        DecisionResult result = DecisionPayloadDecoder.decode(
                request, Map.of("answers", Map.of("c", Map.of("label", "a", "confidence", "0.9"))));

        assertTrue(result.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                result.asNoOpinion().reason());
    }

    @Test
    void opinionToStringDoesNotExposeAnswers() {
        QuestionKey<Choice> key = QuestionKey.choice("sensitive");
        DecisionResult.Opinion opinion = DecisionResult.opinionBuilder("safe.id", 1)
                .put(key, new Choice("SECRET_ANSWER"))
                .ruleId("test-rule")
                .build();

        assertFalse(opinion.toString().contains("SECRET_ANSWER"));
        assertTrue(opinion.toString().contains("answerCount=1"));
    }

    @Test
    void malformedOptionalFieldsFailClosedToInferenceError() {
        var choiceKey = QuestionKey.choice("c");
        var scoreKey = QuestionKey.score("s");

        var request = DecisionRequest.builder("d1", 1, "state")
                .addQuestion(DecisionQuestion.choice(choiceKey, "crit", List.of("a", "b")))
                .addQuestion(DecisionQuestion.score(scoreKey, "crit", List.of("L1", "L2")))
                .build();

        // 1. "probabilities" field is a string instead of a map
        Map<String, Object> badChoiceProbs =
                Map.of("answers", Map.of("c", Map.of("label", "a", "probabilities", "not-a-map"), "s", 0.5));
        DecisionResult r1 = DecisionPayloadDecoder.decode(request, badChoiceProbs);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                r1.asNoOpinion().reason());

        // 2. "confidence" is not a valid number
        Map<String, Object> badConfidence =
                Map.of("answers", Map.of("c", Map.of("label", "a", "confidence", "invalid-float"), "s", 0.5));
        DecisionResult r2 = DecisionPayloadDecoder.decode(request, badConfidence);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                r2.asNoOpinion().reason());

        // 3. Score "legend" is a string instead of a list
        Map<String, Object> badScoreLegend =
                Map.of("answers", Map.of("c", "a", "s", Map.of("score", 1.0, "legend", "not-a-list")));
        DecisionResult r3 = DecisionPayloadDecoder.decode(request, badScoreLegend);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                r3.asNoOpinion().reason());
    }

    @Test
    void unknownAnswerHandlesFailClosedToInferenceError() {
        var choiceKey = QuestionKey.choice("known_q");
        var request = DecisionRequest.builder("d1", 1, "state")
                .addQuestion(DecisionQuestion.choice(choiceKey, "crit", List.of("a", "b")))
                .build();

        // Submitted answers contains unexpected handle 'extra_q'
        Map<String, Object> raw = Map.of(
                "answers",
                Map.of(
                        "known_q", "a",
                        "extra_q", "surprise"));

        DecisionResult result = DecisionPayloadDecoder.decode(request, raw);
        assertTrue(result.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                result.asNoOpinion().reason());
        assertTrue(result.asNoOpinion().detail().contains("Unknown answer handle"));
    }

    @Test
    void malformedPartialAndWrongPrimitivePayloads() {
        var choiceKey = QuestionKey.choice("c");
        var noulKey = QuestionKey.noul("n");

        var request = DecisionRequest.builder("d1", 1, "state")
                .addQuestion(DecisionQuestion.choice(choiceKey, "choose", List.of("alpha", "beta")))
                .addQuestion(DecisionQuestion.noul(noulKey, "affirm"))
                .build();

        DecisionResult rNull = DecisionPayloadDecoder.decode(request, null);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                rNull.asNoOpinion().reason());

        Map<String, Object> partial = Map.of("answers", Map.of("c", "alpha"));
        DecisionResult rPartial = DecisionPayloadDecoder.decode(request, partial);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                rPartial.asNoOpinion().reason());
        assertTrue(rPartial.asNoOpinion().detail().contains("missing answer for handle 'n'"));

        Map<String, Object> wrongPrim = Map.of("answers", Map.of("c", 0.99, "n", 0.5));
        DecisionResult rWrong = DecisionPayloadDecoder.decode(request, wrongPrim);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                rWrong.asNoOpinion().reason());

        Map<String, Object> badOption = Map.of("answers", Map.of("c", "gamma", "n", 0.5));
        DecisionResult rBadOpt = DecisionPayloadDecoder.decode(request, badOption);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                rBadOpt.asNoOpinion().reason());

        Map<String, Object> badNoul = Map.of("answers", Map.of("c", "alpha", "n", 1.5));
        DecisionResult rBadNoul = DecisionPayloadDecoder.decode(request, badNoul);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                rBadNoul.asNoOpinion().reason());

        var singleBuilder = DecisionRequest.builder("single", 1, "state");
        QuestionKey<Choice> routeKey =
                singleBuilder.addChoice("route", "route intent", List.of("coder", "coordinator"));
        var singleRequest = singleBuilder.build();
        Map<String, Object> legacyPayload = Map.of(
                "value", "coder",
                "confidence", 0.88,
                "ruleId", "legacy-rule");
        DecisionResult rLegacy = DecisionPayloadDecoder.decode(singleRequest, legacyPayload);
        assertTrue(rLegacy.isOpinion());
        Choice legacyChoice = rLegacy.asOpinion().get(routeKey);
        assertEquals("coder", legacyChoice.label());
        assertEquals(0.88, legacyChoice.confidence().getAsDouble(), 1e-6);
        assertEquals("legacy-rule", rLegacy.ruleId());
    }

    @Test
    void choiceProbabilitiesOutsideDeclaredOptionSetFailClosed() {
        var builder = DecisionRequest.builder("d1", 1, "state");
        QuestionKey<Choice> choiceKey = builder.addChoice("c", "choose", List.of("alpha", "beta"));
        DecisionRequest request = builder.build();

        // A distribution confined to the declared options is accepted.
        Map<String, Object> declared = Map.of(
                "answers", Map.of("c", Map.of("choice", "alpha", "probabilities", Map.of("alpha", 0.9, "beta", 0.1))));
        DecisionResult accepted = DecisionPayloadDecoder.decode(request, declared);
        assertTrue(accepted.isOpinion());
        assertEquals(2, accepted.asOpinion().get(choiceKey).probabilities().size());

        // An extra provider-chosen key names an option no policy declared. It must not be carried into the
        // answer, where it would silently widen the closed option set for any downstream metric tag.
        Map<String, Object> injected = Map.of(
                "answers",
                Map.of(
                        "c",
                        Map.of(
                                "choice",
                                "alpha",
                                "probabilities",
                                Map.of("alpha", 0.8, "beta", 0.1, "attacker_supplied", 0.1))));
        DecisionResult rejected = DecisionPayloadDecoder.decode(request, injected);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                rejected.asNoOpinion().reason());
    }

    @Test
    void scoreLegendOutsideDeclaredRubricFailsClosed() {
        var builder = DecisionRequest.builder("d1", 1, "state");
        QuestionKey<Score> scoreKey = builder.addScore("s", "rate", List.of("low", "medium", "high"));
        DecisionRequest request = builder.build();

        Map<String, Object> declared =
                Map.of("answers", Map.of("s", Map.of("score", 1.0, "legend", List.of("low", "medium", "high"))));
        DecisionResult accepted = DecisionPayloadDecoder.decode(request, declared);
        assertTrue(accepted.isOpinion());
        assertEquals(
                List.of("low", "medium", "high"),
                accepted.asOpinion().get(scoreKey).legend());

        Map<String, Object> injected = Map.of(
                "answers", Map.of("s", Map.of("score", 1.0, "legend", List.of("low", "unregistered_level", "high"))));
        DecisionResult rejected = DecisionPayloadDecoder.decode(request, injected);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                rejected.asNoOpinion().reason());

        // A provider that omits the legend entirely still gets the declared rubric, unchanged.
        Map<String, Object> omitted = Map.of("answers", Map.of("s", Map.of("score", 1.0)));
        DecisionResult defaulted = DecisionPayloadDecoder.decode(request, omitted);
        assertTrue(defaulted.isOpinion());
        assertEquals(
                List.of("low", "medium", "high"),
                defaulted.asOpinion().get(scoreKey).legend());
    }

    @Test
    void legacyBridgeDoesNotInventCertainty() {
        var choiceKey = QuestionKey.choice("role");

        // Opinion with explicit confidence and ruleId converts to ClassifierResult.Opinion
        var opWithConf = new DecisionResult.Opinion(
                "d", 1, choiceKey, new Choice("coder", OptionalDouble.of(0.9)), "rule-1", DecisionMetadata.empty());
        ClassifierResult<String> clResult = opWithConf.toClassifierResult(choiceKey);
        assertTrue(clResult instanceof ClassifierResult.Opinion<String>);
        assertEquals(0.9f, ((ClassifierResult.Opinion<String>) clResult).confidence(), 1e-6);

        // Opinion with absent confidence returns NoOpinion(LOW_CONFIDENCE) without inventing 1.0f
        var opWithoutConf =
                new DecisionResult.Opinion("d", 1, choiceKey, new Choice("coder"), "rule-1", DecisionMetadata.empty());
        ClassifierResult<String> clWithoutConf = opWithoutConf.toClassifierResult(choiceKey);
        assertTrue(clWithoutConf instanceof ClassifierResult.NoOpinion<String>);
        assertEquals(
                ClassifierResult.NoOpinionReason.LOW_CONFIDENCE,
                ((ClassifierResult.NoOpinion<String>) clWithoutConf).reason());

        // Opinion with absent ruleId returns NoOpinion(INFERENCE_ERROR) without inventing "decision-plane"
        var opWithoutRule = new DecisionResult.Opinion(
                "d", 1, choiceKey, new Choice("coder", OptionalDouble.of(0.9)), null, DecisionMetadata.empty());
        ClassifierResult<String> clWithoutRule = opWithoutRule.toClassifierResult(choiceKey);
        assertTrue(clWithoutRule instanceof ClassifierResult.NoOpinion<String>);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                ((ClassifierResult.NoOpinion<String>) clWithoutRule).reason());
    }

    @Test
    void decisionRequestToStringIsPayloadFree() {
        var requestBuilder = DecisionRequest.builder("sec.req", 1, "SECRET_PAYLOAD_HERE");
        requestBuilder.addChoice("intent", "Select intent", List.of("opt1", "opt2"));
        var request = requestBuilder.build();

        String diag = request.toString();
        assertTrue(diag.contains("sec.req"));
        assertTrue(diag.contains("intent"));
        assertFalse(diag.contains("SECRET_PAYLOAD_HERE"), "toString must not leak state payload");
    }

    @Test
    void directOpinionConstructionValidatesAnswerType() {
        QuestionKey<Choice> choiceKey = QuestionKey.choice("role");
        Score scoreAnswer = new Score(1.0);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<QuestionKey<?>, DecisionAnswer> mismatched = Map.of((QuestionKey) choiceKey, scoreAnswer);
        assertThrows(IllegalArgumentException.class, () -> new DecisionResult.Opinion("test.decision", 1, mismatched));
    }

    @Test
    void opinionBuilderIsPublicAndFunctional() {
        QuestionKey<Choice> choiceKey = QuestionKey.choice("role");
        DecisionResult.OpinionBuilder builder = new DecisionResult.OpinionBuilder("test.builder", 1);
        builder.put(choiceKey, new Choice("coder"));
        builder.ruleId("rule-42");
        DecisionResult.Opinion opinion = builder.build();
        assertEquals("test.builder", opinion.decisionId());
        assertEquals("coder", opinion.get(choiceKey).label());
        assertEquals("rule-42", opinion.ruleId());
    }

    @Test
    void noOpinionSanitizesAndRedactsSecretsInDetail() {
        String testToken = "secret" + "-tok-" + "12345678";
        String testKey = "sk-" + "1234567890123456";
        String secretDetail = "Failed calling http://user:pass@host/api with Authorization: Bearer " + testToken
                + " and api_" + "key=" + testKey;
        var noOpinion = new DecisionResult.NoOpinion(
                "test.decision", 1, ClassifierResult.NoOpinionReason.INFERENCE_ERROR, "rule-1", secretDetail);
        String clean = noOpinion.optionalDetail().orElseThrow();
        assertFalse(clean.contains("pass@host"));
        assertFalse(clean.contains(testToken));
        assertFalse(clean.contains(testKey));
        assertTrue(clean.contains("[REDACTED]"));
        assertFalse(noOpinion.toString().contains("[REDACTED]"));
        assertFalse(noOpinion.toString().contains("secret"));
    }

    @Test
    void providerPlacementValidatorEnforcesPlacementOnCandidateAndEngine() {
        InProcessDecisionEngine validEngine =
                request -> new DecisionResult.NoOpinion(ClassifierResult.NoOpinionReason.DISABLED);
        // Valid candidate passes
        ProviderPlacementValidator.validateClassifierSpiPlacement((Object) validEngine);
        assertSame(validEngine, ProviderPlacementValidator.activateForClassifierSpi(validEngine));
        assertSame(validEngine, validEngine.activateForClassifierSpi());

        // Null candidate fails
        assertThrows(
                SecurityException.class,
                () -> ProviderPlacementValidator.validateClassifierSpiPlacement((Object) null));

        // Remote provider fails
        RemoteDecisionProvider remote = new RemoteDecisionProvider() {};
        assertThrows(
                SecurityException.class,
                () -> ProviderPlacementValidator.validateClassifierSpiPlacement((Object) remote));

        // Self-hosted provider fails
        SelfHostedDecisionProvider selfHosted = new SelfHostedDecisionProvider() {};
        assertThrows(
                SecurityException.class,
                () -> ProviderPlacementValidator.validateClassifierSpiPlacement((Object) selfHosted));
    }
}
