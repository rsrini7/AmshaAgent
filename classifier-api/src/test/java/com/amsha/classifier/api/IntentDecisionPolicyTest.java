package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit and boundary tests for {@link IntentDecisionPolicy}.
 */
class IntentDecisionPolicyTest {

    private static final String DECISION_ID = "amsha.routing.intent.v1";
    private static final int SCHEMA_VERSION = 2;
    private static final Set<String> VALID_ROLES = Set.of("coordinator", "coder", "researcher", "data_analyst");
    private static final ReviewBand BAND = new ReviewBand(0.50, 0.85);

    @Test
    @DisplayName("null result produces deterministic fallback with INFERENCE_ERROR")
    void nullResultProducesFallback() {
        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(null, DECISION_ID, SCHEMA_VERSION, null, VALID_ROLES, BAND);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertTrue(res.selectedRole().isEmpty());
        assertEquals(ClassifierResult.NoOpinionReason.INFERENCE_ERROR.name(), res.reason());
    }

    @Test
    @DisplayName("NoOpinion with TIMEOUT preserves reason without inventing confidence")
    void noOpinionPreservesReason() {
        DecisionResult noOp = new DecisionResult.NoOpinion(
                DECISION_ID, SCHEMA_VERSION, ClassifierResult.NoOpinionReason.TIMEOUT, null, "timeout occurred");
        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(noOp, DECISION_ID, SCHEMA_VERSION, null, VALID_ROLES, BAND);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertTrue(res.confidence().isEmpty());
        assertEquals("TIMEOUT", res.reason());
    }

    @Test
    @DisplayName("unmapped primitive (Noul instead of Choice) produces fallback")
    void unmappedPrimitiveProducesFallback() {
        QuestionKey<Noul> key = QuestionKey.noul("intent_prop");
        DecisionResult opinion = DecisionResult.opinionBuilder(DECISION_ID, SCHEMA_VERSION)
                .put(key, new Noul(0.95))
                .ruleId("rule-1")
                .build();
        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(opinion, DECISION_ID, SCHEMA_VERSION, null, VALID_ROLES, BAND);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals("UNMAPPED_PRIMITIVE", res.reason());
    }

    @Test
    @DisplayName("unrecognized role label outside valid closed set produces fallback")
    void unrecognizedRoleProducesFallback() {
        QuestionKey<Choice> key = QuestionKey.choice("intent_choice");
        DecisionResult opinion = createChoiceResult(key, "unknown_hacker_role", OptionalDouble.of(0.99));
        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(opinion, DECISION_ID, SCHEMA_VERSION, key, VALID_ROLES, BAND);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals("UNRECOGNIZED_ROLE_LABEL", res.reason());
    }

    @Test
    @DisplayName("missing confidence on choice produces fallback")
    void missingConfidenceProducesFallback() {
        QuestionKey<Choice> key = QuestionKey.choice("intent_choice");
        DecisionResult opinion = createChoiceResult(key, "coder", OptionalDouble.empty());
        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(opinion, DECISION_ID, SCHEMA_VERSION, key, VALID_ROLES, BAND);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals("MISSING_CONFIDENCE", res.reason());
    }

    @Test
    @DisplayName("low confidence (< 0.50) produces deterministic fallback")
    void lowConfidenceProducesFallback() {
        QuestionKey<Choice> key = QuestionKey.choice("intent_choice");
        DecisionResult opinion = createChoiceResult(key, "coder", OptionalDouble.of(0.42));
        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(opinion, DECISION_ID, SCHEMA_VERSION, key, VALID_ROLES, BAND);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals(0.42, res.confidence().orElse(0.0), 0.001);
        assertEquals("LOW_CONFIDENCE", res.reason());
    }

    @Test
    @DisplayName("reserved NO_OPINION choice produces deterministic fallback regardless of confidence")
    void explicitNoOpinionChoiceProducesFallback() {
        QuestionKey<Choice> key = QuestionKey.choice("intent_choice");
        DecisionResult opinion =
                createChoiceResult(key, IntentDecisionPolicy.NO_OPINION_LABEL, OptionalDouble.of(0.99));

        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(opinion, DECISION_ID, SCHEMA_VERSION, key, VALID_ROLES, BAND);

        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertTrue(res.selectedRole().isEmpty());
        assertEquals(0.99, res.confidence().orElseThrow(), 0.001);
        assertEquals("MODEL_ABSTAINED", res.reason());
    }

    @Test
    @DisplayName("middle review band ([0.50, 0.85)) produces secondary review signal")
    void middleBandProducesSecondaryReview() {
        QuestionKey<Choice> key = QuestionKey.choice("intent_choice");
        DecisionResult opinion = createChoiceResult(key, "researcher", OptionalDouble.of(0.72));
        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(opinion, DECISION_ID, SCHEMA_VERSION, key, VALID_ROLES, BAND);
        assertEquals(IntentDecisionAction.SECONDARY_REVIEW, res.action());
        assertFalse(res.fallback());
        assertEquals(Optional.of("researcher"), res.selectedRole());
        assertEquals(0.72, res.confidence().orElse(0.0), 0.001);
        assertEquals("MIDDLE_REVIEW_BAND", res.reason());
    }

    @Test
    @DisplayName("high confidence (>= 0.85) produces direct route")
    void highConfidenceProducesDirectRoute() {
        QuestionKey<Choice> key = QuestionKey.choice("intent_choice");
        DecisionResult opinion = createChoiceResult(key, "coder", OptionalDouble.of(0.94));
        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(opinion, DECISION_ID, SCHEMA_VERSION, key, VALID_ROLES, BAND);
        assertEquals(IntentDecisionAction.DIRECT_ROUTE, res.action());
        assertFalse(res.fallback());
        assertEquals(Optional.of("coder"), res.selectedRole());
        assertEquals(0.94, res.confidence().orElse(0.0), 0.001);
        assertEquals("HIGH_CONFIDENCE_ROUTE", res.reason());
    }

    @Test
    @DisplayName("decisionId mismatch rejects to deterministic fallback before using answer")
    void decisionIdMismatchProducesFallback() {
        QuestionKey<Choice> key = QuestionKey.choice("intent_choice");
        // Result is for tool-risk, not intent
        DecisionResult foreignOpinion = DecisionResult.opinionBuilder("amsha.security.tool-risk.v1", 1)
                .put(key, new Choice("coder", 0.99))
                .ruleId("rule-1")
                .build();
        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(foreignOpinion, DECISION_ID, SCHEMA_VERSION, key, VALID_ROLES, BAND);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertTrue(res.selectedRole().isEmpty());
        assertEquals("DECISION_ID_MISMATCH", res.reason());
        assertEquals(DECISION_ID, res.decisionId());
    }

    @Test
    @DisplayName("schemaVersion mismatch rejects to deterministic fallback before using answer")
    void schemaVersionMismatchProducesFallback() {
        QuestionKey<Choice> key = QuestionKey.choice("intent_choice");
        // Result has the next schema version instead of the expected one.
        DecisionResult foreignOpinion = DecisionResult.opinionBuilder(DECISION_ID, SCHEMA_VERSION + 1)
                .put(key, new Choice("coder", 0.99))
                .ruleId("rule-1")
                .build();
        IntentPolicyResolution res =
                IntentDecisionPolicy.resolve(foreignOpinion, DECISION_ID, SCHEMA_VERSION, key, VALID_ROLES, BAND);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertTrue(res.selectedRole().isEmpty());
        assertEquals("SCHEMA_VERSION_MISMATCH", res.reason());
        assertEquals(SCHEMA_VERSION, res.schemaVersion());
    }

    @Test
    @DisplayName("expected result identity is mandatory")
    void expectedIdentityIsRequired() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IntentDecisionPolicy.resolve(null, null, SCHEMA_VERSION, null, VALID_ROLES, BAND));
        assertThrows(
                IllegalArgumentException.class,
                () -> IntentDecisionPolicy.resolve(null, DECISION_ID, 0, null, VALID_ROLES, BAND));
    }

    private static DecisionResult createChoiceResult(QuestionKey<Choice> key, String label, OptionalDouble conf) {
        Choice choice = conf.isPresent() ? new Choice(label, conf.getAsDouble()) : new Choice(label);
        return DecisionResult.opinionBuilder(DECISION_ID, SCHEMA_VERSION)
                .put(key, choice)
                .ruleId("rule-1")
                .build();
    }
}
