package com.amsha.core.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Map;
import java.util.OptionalDouble;

import com.amsha.classifier.api.AnswerQualityPrediction;
import com.amsha.classifier.api.AnswerQualityShadowContext;
import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionMetadata;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.InMemoryDecisionEngine;
import com.amsha.classifier.api.InProcessDecisionEngine;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.RemoteDecisionProvider;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DecisionShadowClassifierTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void intentShadowDisabledByDefaultReturnsDisabled() {
        var engine = InMemoryDecisionEngine.builder().build();
        var classifier = new DecisionIntentShadowClassifier(engine, false);

        ClassifierResult<String> result = classifier.classify("please write a python script");
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<String>) result).reason());
    }

    @Test
    void intentShadowWithMissingEngineReturnsMissingModel() {
        Instance<DecisionEngine> engineInstance = (Instance<DecisionEngine>) mock(Instance.class);
        when(engineInstance.isResolvable()).thenReturn(false);

        DecisionIntentShadowClassifier classifier = new DecisionIntentShadowClassifier(engineInstance, true);

        ClassifierResult<String> result = classifier.classify("research java libraries");
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.MISSING_MODEL, ((ClassifierResult.NoOpinion<String>) result).reason());
    }

    @Test
    void intentShadowEvaluatesOpinionAndPreservesConfidence() {
        var engine = InMemoryDecisionEngine.builder()
                .withAnswer(QuestionKey.choice("role"), new Choice("coder", OptionalDouble.of(0.92)))
                .withRuleId("rule-intent-coder")
                .build();
        var classifier = new DecisionIntentShadowClassifier(engine, true);

        ClassifierResult<String> result = classifier.classify("write calculator in python");
        assertInstanceOf(ClassifierResult.Opinion.class, result);
        var opinion = (ClassifierResult.Opinion<String>) result;
        assertEquals("coder", opinion.value());
        assertEquals(0.92f, opinion.confidence(), 1e-4f);
        assertEquals("rule-intent-coder", opinion.ruleId());
    }

    @Test
    void placementValidationBlocksNonInProcessEngine() {
        class RemoteEngineStub implements DecisionEngine, RemoteDecisionProvider {
            @Override
            public DecisionResult evaluate(DecisionRequest request) {
                return new DecisionResult.NoOpinion(
                        request.decisionId(), request.schemaVersion(), NoOpinionReason.DISABLED);
            }

            @Override
            public ProviderCategory category() {
                return ProviderCategory.REMOTE;
            }
        }
        var remote = new RemoteEngineStub();
        Assertions.assertThrows(SecurityException.class, () -> new DecisionIntentShadowClassifier(remote, true));
        Assertions.assertThrows(
                SecurityException.class, () -> new DecisionToolRiskShadowClassifier(remote, mapper, true));
        Assertions.assertThrows(
                SecurityException.class, () -> new DecisionAnswerQualityShadowClassifier(remote, mapper, true));
    }

    @Test
    void requestIdentityMismatchFailsClosedToInferenceError() {
        var wrongResult = new DecisionResult.Opinion(
                "foreign.decision.id",
                1,
                Map.of(DecisionIntentShadowClassifier.ROLE_KEY, new Choice("coder", OptionalDouble.of(0.9))),
                "rule-1",
                DecisionMetadata.empty());
        var engine = InMemoryDecisionEngine.builder().withResult(wrongResult).build();
        var classifier = new DecisionIntentShadowClassifier(engine, true);

        ClassifierResult<String> result = classifier.classify("test task");
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.INFERENCE_ERROR, ((ClassifierResult.NoOpinion<String>) result).reason());
    }

    @Test
    void missingConfidenceReturnsLowConfidenceAbstentionWithoutInventingZero() {
        var engine = InMemoryDecisionEngine.builder()
                .withAnswer(QuestionKey.choice("role"), new Choice("coder", OptionalDouble.empty()))
                .withRuleId("rule-without-conf")
                .build();
        var classifier = new DecisionIntentShadowClassifier(engine, true);

        ClassifierResult<String> result = classifier.classify("code a game");
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.LOW_CONFIDENCE, ((ClassifierResult.NoOpinion<String>) result).reason());
    }

    @Test
    void missingRuleIdFailsClosedToInferenceError() {
        var engine = InMemoryDecisionEngine.builder()
                .withAnswer(QuestionKey.choice("role"), new Choice("coder", OptionalDouble.of(0.95)))
                // ruleId is omitted, defaults to null
                .build();
        var classifier = new DecisionIntentShadowClassifier(engine, true);

        ClassifierResult<String> result = classifier.classify("code a game");
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.INFERENCE_ERROR, ((ClassifierResult.NoOpinion<String>) result).reason());
    }

    @Test
    void intentShadowFailsClosedOnException() {
        InProcessDecisionEngine failingEngine = (DecisionRequest request) -> {
            throw new RuntimeException("Simulated provider crash");
        };
        var classifier = new DecisionIntentShadowClassifier(failingEngine, true);

        ClassifierResult<String> result = classifier.classify("crash test");
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.INFERENCE_ERROR, ((ClassifierResult.NoOpinion<String>) result).reason());
    }

    @Test
    void toolRiskShadowEvaluatesPredictionsCorrectly() {
        var blockEngine = InMemoryDecisionEngine.builder()
                .withAnswer(
                        DecisionToolRiskShadowClassifier.TOOL_RISK_KEY, new Choice("BLOCK", OptionalDouble.of(0.99)))
                .withRuleId("rule-block-rm")
                .build();
        var blockClassifier = new DecisionToolRiskShadowClassifier(blockEngine, mapper, true);

        ToolRiskShadowContext blockContext = new ToolRiskShadowContext(
                "coder", AutonomyLevel.FULL, "execute_shell", Map.of("cmd", "rm -rf /"), "clean disk");

        ClassifierResult<ToolRiskPrediction> blockResult = blockClassifier.classify(blockContext);
        assertInstanceOf(ClassifierResult.Opinion.class, blockResult);
        var blockOpinion = (ClassifierResult.Opinion<ToolRiskPrediction>) blockResult;
        assertEquals(ToolRiskPrediction.BLOCK, blockOpinion.value());
        assertEquals(0.99f, blockOpinion.confidence(), 1e-4f);
        assertEquals("rule-block-rm", blockOpinion.ruleId());

        var approvalEngine = InMemoryDecisionEngine.builder()
                .withAnswer(
                        DecisionToolRiskShadowClassifier.TOOL_RISK_KEY,
                        new Choice("REQUIRE_APPROVAL", OptionalDouble.of(0.75)))
                .withRuleId("rule-approval-needed")
                .build();
        var approvalClassifier = new DecisionToolRiskShadowClassifier(approvalEngine, mapper, true);

        ClassifierResult<ToolRiskPrediction> approvalResult = approvalClassifier.classify(blockContext);
        assertInstanceOf(ClassifierResult.Opinion.class, approvalResult);
        assertEquals(
                ToolRiskPrediction.REQUIRE_APPROVAL,
                ((ClassifierResult.Opinion<ToolRiskPrediction>) approvalResult).value());

        var allowEngine = InMemoryDecisionEngine.builder()
                .withAnswer(
                        DecisionToolRiskShadowClassifier.TOOL_RISK_KEY, new Choice("LOW_RISK", OptionalDouble.of(0.85)))
                .withRuleId("rule-allow")
                .build();
        var allowClassifier = new DecisionToolRiskShadowClassifier(allowEngine, mapper, true);

        ClassifierResult<ToolRiskPrediction> allowResult = allowClassifier.classify(blockContext);
        assertInstanceOf(ClassifierResult.Opinion.class, allowResult);
        var allowOp = (ClassifierResult.Opinion<ToolRiskPrediction>) allowResult;
        assertEquals(ToolRiskPrediction.LOW_RISK, allowOp.value());
        assertEquals(0.85f, allowOp.confidence(), 1e-4f);
    }

    @Test
    void toolRiskShadowMissingConfidenceReturnsLowConfidenceAbstention() {
        var allowEngine = InMemoryDecisionEngine.builder()
                .withAnswer(
                        DecisionToolRiskShadowClassifier.TOOL_RISK_KEY, new Choice("LOW_RISK", OptionalDouble.empty()))
                .withRuleId("rule-allow")
                .build();
        var allowClassifier = new DecisionToolRiskShadowClassifier(allowEngine, mapper, true);

        ToolRiskShadowContext context = new ToolRiskShadowContext(
                "coder", AutonomyLevel.FULL, "read_file", Map.of("path", "README.md"), "view doc");

        ClassifierResult<ToolRiskPrediction> result = allowClassifier.classify(context);
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(
                NoOpinionReason.LOW_CONFIDENCE, ((ClassifierResult.NoOpinion<ToolRiskPrediction>) result).reason());
    }

    @Test
    void answerQualityShadowEvaluatesPredictionsCorrectly() {
        var passEngine = InMemoryDecisionEngine.builder()
                .withAnswer(
                        DecisionAnswerQualityShadowClassifier.ANSWER_QUALITY_KEY,
                        new Choice("ACCEPTABLE", OptionalDouble.of(0.88)))
                .withRuleId("rule-pass")
                .build();
        var passClassifier = new DecisionAnswerQualityShadowClassifier(passEngine, mapper, true);

        AnswerQualityShadowContext context = new AnswerQualityShadowContext(
                "researcher", "explain photosynthesis", "Photosynthesis is the process...", true);

        ClassifierResult<AnswerQualityPrediction> passResult = passClassifier.classify(context);
        assertInstanceOf(ClassifierResult.Opinion.class, passResult);
        assertEquals(
                AnswerQualityPrediction.ACCEPTABLE,
                ((ClassifierResult.Opinion<AnswerQualityPrediction>) passResult).value());

        var retryEngine = InMemoryDecisionEngine.builder()
                .withAnswer(
                        DecisionAnswerQualityShadowClassifier.ANSWER_QUALITY_KEY,
                        new Choice("RETRY_RECOMMENDED", OptionalDouble.of(0.65)))
                .withRuleId("rule-retry")
                .build();
        var retryClassifier = new DecisionAnswerQualityShadowClassifier(retryEngine, mapper, true);

        ClassifierResult<AnswerQualityPrediction> retryResult = retryClassifier.classify(context);
        assertInstanceOf(ClassifierResult.Opinion.class, retryResult);
        assertEquals(
                AnswerQualityPrediction.RETRY_RECOMMENDED,
                ((ClassifierResult.Opinion<AnswerQualityPrediction>) retryResult).value());
    }

    @Test
    void answerQualityShadowMissingConfidenceReturnsLowConfidenceAbstention() {
        var engine = InMemoryDecisionEngine.builder()
                .withAnswer(
                        DecisionAnswerQualityShadowClassifier.ANSWER_QUALITY_KEY,
                        new Choice("ACCEPTABLE", OptionalDouble.empty()))
                .withRuleId("rule-pass")
                .build();
        var classifier = new DecisionAnswerQualityShadowClassifier(engine, mapper, true);

        AnswerQualityShadowContext context = new AnswerQualityShadowContext("researcher", "goal", "ans", false);

        ClassifierResult<AnswerQualityPrediction> result = classifier.classify(context);
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(
                NoOpinionReason.LOW_CONFIDENCE,
                ((ClassifierResult.NoOpinion<AnswerQualityPrediction>) result).reason());
    }

    @Test
    void unregisteredChoiceOptionsAreRejectedAtClassifierBoundary() {
        // 1. Tool risk unregistered aliases like ALLOW or PAUSE must fail closed
        var allowEngine = InMemoryDecisionEngine.builder()
                .withAnswer(
                        DecisionToolRiskShadowClassifier.TOOL_RISK_KEY, new Choice("ALLOW", OptionalDouble.of(0.95)))
                .withRuleId("rule-allow")
                .build();
        var toolRiskClassifier = new DecisionToolRiskShadowClassifier(allowEngine, mapper, true);
        ToolRiskShadowContext riskCtx =
                new ToolRiskShadowContext("coder", AutonomyLevel.FULL, "read_file", Map.of("path", "file.txt"), "read");
        ClassifierResult<ToolRiskPrediction> riskResult = toolRiskClassifier.classify(riskCtx);
        assertInstanceOf(ClassifierResult.NoOpinion.class, riskResult);
        assertEquals(
                NoOpinionReason.INFERENCE_ERROR,
                ((ClassifierResult.NoOpinion<ToolRiskPrediction>) riskResult).reason());

        // 2. Answer quality unregistered aliases like GOOD or PASS must fail closed
        var goodEngine = InMemoryDecisionEngine.builder()
                .withAnswer(
                        DecisionAnswerQualityShadowClassifier.ANSWER_QUALITY_KEY,
                        new Choice("GOOD", OptionalDouble.of(0.90)))
                .withRuleId("rule-good")
                .build();
        var answerClassifier = new DecisionAnswerQualityShadowClassifier(goodEngine, mapper, true);
        AnswerQualityShadowContext answerCtx = new AnswerQualityShadowContext("researcher", "goal", "ans", false);
        ClassifierResult<AnswerQualityPrediction> qualityResult = answerClassifier.classify(answerCtx);
        assertInstanceOf(ClassifierResult.NoOpinion.class, qualityResult);
        assertEquals(
                NoOpinionReason.INFERENCE_ERROR,
                ((ClassifierResult.NoOpinion<AnswerQualityPrediction>) qualityResult).reason());

        // 3. Intent unregistered role labels must fail closed
        var unknownRoleEngine = InMemoryDecisionEngine.builder()
                .withAnswer(QuestionKey.choice("role"), new Choice("non_existent_role", OptionalDouble.of(0.90)))
                .withRuleId("rule-role")
                .build();
        var intentClassifier = new DecisionIntentShadowClassifier(unknownRoleEngine, true);
        ClassifierResult<String> intentResult = intentClassifier.classify("do something");
        assertInstanceOf(ClassifierResult.NoOpinion.class, intentResult);
        assertEquals(NoOpinionReason.INFERENCE_ERROR, ((ClassifierResult.NoOpinion<String>) intentResult).reason());
    }
}
