package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Unit and contract tests for the Jev decision plane vocabulary primitives:
 * Choice, Score, Noul, DecisionQuestion, DecisionDefinition, and provider placement.
 */
class DecisionPlaneVocabularyTest {

    @Test
    void validChoiceConstruction() {
        var choice1 = new Choice("coder");
        assertEquals("coder", choice1.label());
        assertTrue(choice1.confidence().isEmpty(), "confidence must remain absent");
        assertTrue(choice1.probabilities().isEmpty(), "probabilities must remain absent");
        assertEquals(DecisionPrimitive.CHOICE, choice1.primitive());

        var choice2 = new Choice("researcher", OptionalDouble.of(0.85));
        assertEquals("researcher", choice2.label());
        assertTrue(choice2.confidence().isPresent());
        assertEquals(0.85, choice2.confidence().getAsDouble(), 1e-6);

        Map<String, Double> probs = Map.of("coder", 0.7, "researcher", 0.3);
        var choice3 = new Choice("coder", probs, OptionalDouble.of(0.9));
        assertEquals(0.7, choice3.probability("coder").getAsDouble(), 1e-6);
        assertEquals(0.3, choice3.probability("researcher").getAsDouble(), 1e-6);
        assertTrue(choice3.probability("unknown").isEmpty(), "absent option returns empty");
    }

    @Test
    void validScoreConstruction() {
        var score1 = new Score(2.5);
        assertEquals(2.5, score1.score(), 1e-6);
        assertTrue(score1.confidence().isEmpty());
        assertTrue(score1.probabilities().isEmpty());
        assertTrue(score1.legend().isEmpty());
        assertTrue(score1.mostLikelyLevel().isEmpty(), "absent probabilities means absent mostLikelyLevel");
        assertTrue(score1.normalizedScore().isEmpty(), "absent level count means absent normalizedScore");
        assertEquals(DecisionPrimitive.SCORE, score1.primitive());

        List<String> legend = List.of("Low", "Medium", "High");
        List<Double> probs = List.of(0.1, 0.7, 0.2);
        var score2 = new Score(1.2, probs, legend, OptionalDouble.of(0.8));
        assertEquals(1.2, score2.score(), 1e-6);
        assertEquals(legend, score2.legend());
        assertEquals(probs, score2.probabilities());
        assertEquals(1, score2.mostLikelyLevel().getAsInt(), "highest probability is at index 1");
        assertEquals(1.2 / 2.0, score2.normalizedScore().getAsDouble(), 1e-6);
    }

    @Test
    void validNoulConstruction() {
        var noul1 = new Noul(0.0);
        assertEquals(0.0, noul1.probability(), 1e-6);
        assertEquals(DecisionPrimitive.NOUL, noul1.primitive());
        assertFalse(noul1.isAffirmed(0.5));

        var noul2 = new Noul(1.0);
        assertEquals(1.0, noul2.probability(), 1e-6);
        assertTrue(noul2.isAffirmed(0.8));

        var noul3 = new Noul(0.65);
        assertTrue(noul3.isAffirmed(0.65));
        assertTrue(noul3.isAffirmed(0.60));
        assertFalse(noul3.isAffirmed(0.70));
    }

    @Test
    void invalidCrossFieldCombinationsInChoice() {
        assertThrows(NullPointerException.class, () -> new Choice(null));
        assertThrows(IllegalArgumentException.class, () -> new Choice(" "));
        assertThrows(IllegalArgumentException.class, () -> new Choice("c", OptionalDouble.of(-0.1)));
        assertThrows(IllegalArgumentException.class, () -> new Choice("c", OptionalDouble.of(1.1)));
        assertThrows(IllegalArgumentException.class, () -> new Choice("c", OptionalDouble.of(Double.NaN)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new Choice("coder", Map.of("researcher", 0.5), OptionalDouble.empty()));

        Map<String, Double> badProbs = new HashMap<>();
        badProbs.put("coder", -0.2);
        assertThrows(IllegalArgumentException.class, () -> new Choice("coder", badProbs, OptionalDouble.empty()));
    }

    @Test
    void invalidCrossFieldCombinationsInScore() {
        assertThrows(IllegalArgumentException.class, () -> new Score(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Score(-0.5));
        assertThrows(IllegalArgumentException.class, () -> new Score(1.0, OptionalDouble.of(1.5)));

        assertThrows(
                IllegalArgumentException.class, () -> new Score(0.0, List.of(0.5), List.of(), OptionalDouble.empty()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new Score(0.0, List.of(), List.of("Single"), OptionalDouble.empty()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new Score(1.0, List.of(0.5, 0.5), List.of("L1", "L2", "L3"), OptionalDouble.empty()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new Score(3.5, List.of(0.3, 0.4, 0.3), List.of("L0", "L1", "L2"), OptionalDouble.empty()));
    }

    @Test
    void invalidCrossFieldCombinationsInNoul() {
        assertThrows(IllegalArgumentException.class, () -> new Noul(-0.01));
        assertThrows(IllegalArgumentException.class, () -> new Noul(1.01));
        assertThrows(IllegalArgumentException.class, () -> new Noul(Double.NaN));

        var noul = new Noul(0.5);
        assertThrows(IllegalArgumentException.class, () -> noul.isAffirmed(-0.1));
        assertThrows(IllegalArgumentException.class, () -> noul.isAffirmed(1.1));
        assertThrows(IllegalArgumentException.class, () -> noul.isAffirmed(Double.NaN));
    }

    @Test
    void invalidCrossFieldCombinationsInDecisionQuestion() {
        var choiceKey = QuestionKey.choice("q_choice");
        var scoreKey = QuestionKey.score("q_score");
        var noulKey = QuestionKey.noul("q_noul");

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionQuestion<>(choiceKey, "  ", List.of("a", "b"), List.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionQuestion<>(choiceKey, "criteria", List.of("a"), List.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionQuestion<>(choiceKey, "criteria", List.of("a", "a"), List.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionQuestion<>(choiceKey, "criteria", List.of("a", "b"), List.of("L1", "L2")));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionQuestion<>(scoreKey, "criteria", List.of("a", "b"), List.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionQuestion<>(scoreKey, "criteria", List.of(), List.of("OnlyOne")));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionQuestion<>(noulKey, "criteria", List.of("a", "b"), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionQuestion<>(noulKey, "criteria", List.of(), List.of("L1", "L2")));
    }

    @Test
    void decisionDefinitionAndPoliciesValidation() {
        var reviewBand = new ReviewBand(0.4, 0.8);
        assertTrue(reviewBand.isLow(0.3));
        assertTrue(reviewBand.isReview(0.5));
        assertTrue(reviewBand.isHigh(0.85));

        assertThrows(IllegalArgumentException.class, () -> new ReviewBand(0.9, 0.2));

        var redPolicy = new RedactionPolicy(true, true, Set.of("password", "token"));
        assertTrue(redPolicy.maskedFields().contains("password"));

        var retPolicy = new RetentionPolicy(Duration.ofHours(1), false, false);
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy(Duration.ofSeconds(-1), false, false));

        var candPolicy = new CandidateReductionPolicy("FILTER_ACTIVE", "Active only", 10, List.of("a", "b"));
        assertEquals(10, candPolicy.maxCandidates());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CandidateReductionPolicy("RULE", "rationale", 2, List.of("a", "b", "c")));

        var def = new DecisionDefinition(
                "intent.router",
                1,
                DecisionPrimitive.CHOICE,
                "Select persona",
                "goal.text",
                redPolicy,
                retPolicy,
                candPolicy,
                reviewBand,
                Set.of(ProviderCategory.IN_PROCESS),
                FailMode.FAIL_CLOSED,
                Duration.ofSeconds(2),
                true);

        assertEquals("intent.router", def.decisionId());
        assertEquals(1, def.schemaVersion());
        assertEquals(FailMode.FAIL_CLOSED, def.failMode());
    }

    @Test
    void providerPlacementEnforcesClassifierSpiEligibility() {
        assertTrue(ProviderCategory.IN_PROCESS.permitsClassifierSpi());
        assertFalse(ProviderCategory.SELF_HOSTED.permitsClassifierSpi());
        assertFalse(ProviderCategory.REMOTE.permitsClassifierSpi());

        InProcessDecisionEngine inProcess = request -> new DecisionResult.NoOpinion(
                request.decisionId(), request.schemaVersion(), ClassifierResult.NoOpinionReason.DISABLED);
        assertEquals(ProviderCategory.IN_PROCESS, inProcess.category());
        assertTrue(inProcess.category().permitsClassifierSpi());

        SelfHostedDecisionProvider selfHosted = new SelfHostedDecisionProvider() {};
        assertEquals(ProviderCategory.SELF_HOSTED, selfHosted.category());
        assertFalse(selfHosted.category().permitsClassifierSpi());

        RemoteDecisionProvider remote = new RemoteDecisionProvider() {};
        assertEquals(ProviderCategory.REMOTE, remote.category());
        assertFalse(remote.category().permitsClassifierSpi());
    }

    @Test
    void providerPlacementValidatorStructurallyRejectsNonInProcess() {
        InProcessDecisionEngine validEngine = request -> new DecisionResult.NoOpinion(
                request.decisionId(), request.schemaVersion(), ClassifierResult.NoOpinionReason.DISABLED);
        ProviderPlacementValidator.validateClassifierSpiPlacement(validEngine);

        DecisionEngine plainEngine = request -> new DecisionResult.NoOpinion(
                request.decisionId(), request.schemaVersion(), ClassifierResult.NoOpinionReason.DISABLED);
        assertThrows(
                SecurityException.class, () -> ProviderPlacementValidator.validateClassifierSpiPlacement(plainEngine));

        DecisionEngine remoteEngine = new MockRemoteEngine();
        assertThrows(
                SecurityException.class, () -> ProviderPlacementValidator.validateClassifierSpiPlacement(remoteEngine));

        DecisionEngine selfHostedEngine = new MockSelfHostedEngine();
        assertThrows(
                SecurityException.class,
                () -> ProviderPlacementValidator.validateClassifierSpiPlacement(selfHostedEngine));

        assertThrows(SecurityException.class, () -> ProviderPlacementValidator.validateClassifierSpiPlacement(null));

        ProviderPlacementValidator.validateCategory(ProviderCategory.IN_PROCESS, ProviderCategory.IN_PROCESS);
        assertThrows(
                IllegalArgumentException.class,
                () -> ProviderPlacementValidator.validateCategory(
                        ProviderCategory.IN_PROCESS, ProviderCategory.REMOTE));
    }

    private static final class MockRemoteEngine implements InProcessDecisionEngine, RemoteDecisionProvider {
        @Override
        public DecisionResult evaluate(DecisionRequest request) {
            return null;
        }

        @Override
        public ProviderCategory category() {
            return ProviderCategory.REMOTE;
        }
    }

    private static final class MockSelfHostedEngine implements InProcessDecisionEngine, SelfHostedDecisionProvider {
        @Override
        public DecisionResult evaluate(DecisionRequest request) {
            return null;
        }

        @Override
        public ProviderCategory category() {
            return ProviderCategory.SELF_HOSTED;
        }
    }
}
