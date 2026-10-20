package com.amsha.classifier.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM Native Image reflection configuration for amsha-classifier-api.
 * Contract types moved here from core (extract-core-subsystems S6): the
 * answer-quality/tool-risk prediction family, the shadow-classifier SPIs,
 * {@code IntentShadowClassifier}, {@code ClassifierResult} family, and the
 * decision-plane vocabulary (Jev decision plane Issue 01).
 */
@RegisterForReflection(
        targets = {
            AnswerQualityPrediction.class,
            AnswerQualityAction.class,
            AnswerQualityContext.class,
            AnswerQualityShadowContext.class,
            AnswerQualityShadowClassifier.class,
            ToolRiskPrediction.class,
            ToolRiskAction.class,
            ToolRiskShadowContext.class,
            ToolRiskShadowClassifier.class,
            PostPolicyToolContext.class,
            IntentShadowClassifier.class,
            ClassifierResult.class,
            ShadowClassifier.class,
            DecisionPrimitive.class,
            DecisionAnswer.class,
            Choice.class,
            Score.class,
            Noul.class,
            QuestionKey.class,
            DecisionQuestion.class,
            DecisionRequest.class,
            DecisionResult.class,
            DecisionDefinition.class,
            DecisionMetadata.class,
            ProviderCategory.class,
            RedactionPolicy.class,
            StateProjectionPolicy.class,
            RetentionPolicy.class,
            CandidateReductionPolicy.class,
            ReviewBand.class,
            FailMode.class,
            DecisionEngine.class,
            InProcessDecisionEngine.class,
            SelfHostedDecisionProvider.class,
            RemoteDecisionProvider.class,
            DecisionPayloadDecoder.class,
            ProviderPayloadParser.class,
            ProviderPlacementValidator.class,
            DecisionResult.OpinionBuilder.class,
            InMemoryDecisionEngine.class,
            InMemoryDecisionEngine.Builder.class,
            DecisionTransactionGuard.class,
            TransactionViolationPolicy.class,
            ToolRiskDecisionPolicy.class,
            ToolRiskDecisionPolicy.PolicyResolution.class,
            IntentDecisionAction.class,
            IntentPolicyResolution.class,
            IntentDecisionPolicy.class
        },
        classNames = {
            "com.amsha.classifier.api.ClassifierResult$Opinion",
            "com.amsha.classifier.api.ClassifierResult$NoOpinion",
            "com.amsha.classifier.api.ClassifierResult$NoOpinionReason",
            "com.amsha.classifier.api.DecisionResult$Opinion",
            "com.amsha.classifier.api.DecisionResult$NoOpinion"
        })
public class ClassifierApiReflectionConfig {}
