package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for the shadow-classifier vocabulary moved out of core (S6).
 * Pins enum value sets and closed-result validation so downstream ONNX
 * classifiers cannot silently drift.
 */
class ClassifierContractsTest {

    @Test
    void answerQualityActionIsSingleValued() {
        // Sole bounded action: an answer-quality interceptor may only request one retry.
        assertArrayEquals(new AnswerQualityAction[] {AnswerQualityAction.REQUEST_RETRY}, AnswerQualityAction.values());
    }

    @Test
    void toolRiskActionVocabularyIsStableAndMonotonic() {
        assertArrayEquals(
                new ToolRiskAction[] {ToolRiskAction.REQUIRE_APPROVAL, ToolRiskAction.BLOCK}, ToolRiskAction.values());
    }

    @Test
    void noOpinionReasonVocabularyIsStable() {
        assertArrayEquals(
                new NoOpinionReason[] {
                    NoOpinionReason.DISABLED,
                    NoOpinionReason.MISSING_MODEL,
                    NoOpinionReason.TIMEOUT,
                    NoOpinionReason.OVERLOAD_REJECTED,
                    NoOpinionReason.CIRCUIT_OPEN,
                    NoOpinionReason.LOW_CONFIDENCE,
                    NoOpinionReason.INFERENCE_ERROR
                },
                NoOpinionReason.values());
    }

    @Test
    void opinionAcceptsValidConfidenceAndRule() {
        var opinion = new ClassifierResult.Opinion<>("block", 0.75f, "onnx-tool-risk");

        assertEquals("block", opinion.value());
        assertEquals(0.75f, opinion.confidence());
        assertEquals("onnx-tool-risk", opinion.ruleId());
    }

    @Test
    void opinionRejectsConfidenceOutsideUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> new ClassifierResult.Opinion<>("x", -0.1f, "r"));
        assertThrows(IllegalArgumentException.class, () -> new ClassifierResult.Opinion<>("x", 1.1f, "r"));
        assertThrows(IllegalArgumentException.class, () -> new ClassifierResult.Opinion<>("x", Float.NaN, "r"));
    }

    @Test
    void opinionRejectsBlankRuleIdAndNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new ClassifierResult.Opinion<>("x", 0.5f, " "));
        assertThrows(NullPointerException.class, () -> new ClassifierResult.Opinion<>(null, 0.5f, "r"));
    }

    @Test
    void noOpinionRejectsNullReason() {
        assertThrows(NullPointerException.class, () -> new ClassifierResult.NoOpinion<>(null));
    }

    @Test
    void noOpinionPreservesExplicitReason() {
        var result = new ClassifierResult.NoOpinion<>(NoOpinionReason.CIRCUIT_OPEN);

        assertEquals(NoOpinionReason.CIRCUIT_OPEN, result.reason());
    }

    @Test
    void postPolicyToolContextRejectsBlankRequiredFields() {
        assertThrows(
                NullPointerException.class,
                () -> new PostPolicyToolContext(
                        null,
                        AutonomyLevel.FULL,
                        "sandbox_exec",
                        ToolOperation.EXECUTE_SHELL,
                        Map.of(),
                        "goal",
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PostPolicyToolContext(
                        " ", AutonomyLevel.FULL, "sandbox_exec", ToolOperation.EXECUTE_SHELL, Map.of(), "goal", false));
        assertThrows(
                NullPointerException.class,
                () -> new PostPolicyToolContext(
                        "coder", AutonomyLevel.FULL, null, ToolOperation.EXECUTE_SHELL, Map.of(), "goal", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PostPolicyToolContext(
                        "coder", AutonomyLevel.FULL, " ", ToolOperation.EXECUTE_SHELL, Map.of(), "goal", false));
        assertThrows(
                NullPointerException.class,
                () -> new PostPolicyToolContext(
                        "coder", AutonomyLevel.FULL, "sandbox_exec", null, Map.of(), "goal", false));
    }

    @Test
    void postPolicyToolContextRejectsNullAutonomyAndParameters() {
        assertThrows(
                NullPointerException.class,
                () -> new PostPolicyToolContext(
                        "coder", null, "sandbox_exec", ToolOperation.EXECUTE_SHELL, Map.of(), "goal", false));
        assertThrows(
                NullPointerException.class,
                () -> new PostPolicyToolContext(
                        "coder", AutonomyLevel.FULL, "sandbox_exec", ToolOperation.EXECUTE_SHELL, null, "goal", false));
    }

    @Test
    void postPolicyToolContextDefensivelyCopiesParametersAndToleratesNullGoal() {
        var mutable = new LinkedHashMap<String, Object>();
        mutable.put("command", "ls");

        var context = new PostPolicyToolContext(
                "coder", AutonomyLevel.FULL, "sandbox_exec", ToolOperation.EXECUTE_SHELL, mutable, null, false);
        mutable.put("command", "rm -rf /");

        assertEquals(1, context.parameters().size());
        assertEquals("ls", context.parameters().get("command"), "context must hold a defensive copy");
        assertEquals("", context.goal(), "null goal normalizes to empty string");
        assertThrows(
                UnsupportedOperationException.class, () -> context.parameters().put("x", "y"));
        assertTrue(context.toString().contains("parameterCount=1"), "toString must stay payload-free");
    }

    @Test
    void answerQualityContextRejectsBlankRoleAndNullAnswer() {
        assertThrows(NullPointerException.class, () -> new AnswerQualityContext(null, "goal", "answer", false));
        assertThrows(IllegalArgumentException.class, () -> new AnswerQualityContext(" ", "goal", "answer", false));
        assertThrows(NullPointerException.class, () -> new AnswerQualityContext("coder", "goal", null, false));
    }

    @Test
    void answerQualityContextNormalizesNullGoalAndKeepsSummaryPayloadFree() {
        var context = new AnswerQualityContext("coder", null, "the answer", true);

        assertEquals("", context.goal());
        assertEquals("the answer", context.answer());
        assertTrue(context.toString().contains("coder") && context.toString().contains("toolInvokedInTurn=true"));
        assertFalse(context.toString().contains("the answer"), "toString must not leak the answer payload");
    }

    @Test
    void predictionEnumsAreStable() {
        assertArrayEquals(
                new ToolRiskPrediction[] {
                    ToolRiskPrediction.LOW_RISK, ToolRiskPrediction.REQUIRE_APPROVAL, ToolRiskPrediction.BLOCK
                },
                ToolRiskPrediction.values());
        assertArrayEquals(
                new AnswerQualityPrediction[] {
                    AnswerQualityPrediction.ACCEPTABLE, AnswerQualityPrediction.RETRY_RECOMMENDED
                },
                AnswerQualityPrediction.values());
        assertNotNull(new ClassifierApiReflectionConfig(), "reflection config instantiates");
    }

    @Test
    void toolRiskShadowContextRejectsBlankRequiredFields() {
        assertThrows(
                NullPointerException.class,
                () -> new ToolRiskShadowContext(
                        null, AutonomyLevel.FULL, "sandbox_exec", ToolOperation.EXECUTE_SHELL, Map.of(), "goal"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolRiskShadowContext(
                        " ", AutonomyLevel.FULL, "sandbox_exec", ToolOperation.EXECUTE_SHELL, Map.of(), "goal"));
        assertThrows(
                NullPointerException.class,
                () -> new ToolRiskShadowContext(
                        "coder", AutonomyLevel.FULL, null, ToolOperation.EXECUTE_SHELL, Map.of(), "goal"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolRiskShadowContext(
                        "coder", AutonomyLevel.FULL, " ", ToolOperation.EXECUTE_SHELL, Map.of(), "goal"));
        assertThrows(
                NullPointerException.class,
                () -> new ToolRiskShadowContext("coder", AutonomyLevel.FULL, "sandbox_exec", null, Map.of(), "goal"));
        assertThrows(
                NullPointerException.class,
                () -> new ToolRiskShadowContext(
                        "coder", null, "sandbox_exec", ToolOperation.EXECUTE_SHELL, Map.of(), "goal"));
        assertThrows(
                NullPointerException.class,
                () -> new ToolRiskShadowContext(
                        "coder", AutonomyLevel.FULL, "sandbox_exec", ToolOperation.EXECUTE_SHELL, null, "goal"));
    }

    @Test
    void toolRiskShadowContextDefensivelyCopiesParametersAndKeepsSummaryPayloadFree() {
        var mutable = new LinkedHashMap<String, Object>();
        mutable.put("path", "/tmp");

        var context = new ToolRiskShadowContext(
                "coder", AutonomyLevel.FULL, "sandbox_exec", ToolOperation.EXECUTE_SHELL, mutable, null);
        mutable.put("path", "/etc");

        assertEquals(1, context.parameters().size());
        assertEquals("/tmp", context.parameters().get("path"), "context must hold a defensive copy");
        assertEquals("", context.goal(), "null goal normalizes to empty string");
        assertThrows(
                UnsupportedOperationException.class, () -> context.parameters().put("x", "y"));
        assertTrue(context.toString().contains("parameterCount=1"));
        assertFalse(context.toString().contains("/tmp"), "toString must not leak parameters");
    }

    @Test
    void answerQualityShadowContextValidationMatchesActiveContextContract() {
        assertThrows(NullPointerException.class, () -> new AnswerQualityShadowContext(null, "goal", "answer", false));
        assertThrows(
                IllegalArgumentException.class, () -> new AnswerQualityShadowContext(" ", "goal", "answer", false));
        assertThrows(NullPointerException.class, () -> new AnswerQualityShadowContext("coder", "goal", null, false));

        var context = new AnswerQualityShadowContext("coder", null, "the answer", false);
        assertEquals("", context.goal());
        assertFalse(context.toString().contains("the answer"), "toString must not leak the answer payload");
    }
}
