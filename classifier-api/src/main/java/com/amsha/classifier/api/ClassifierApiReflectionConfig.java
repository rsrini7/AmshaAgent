package com.amsha.classifier.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM Native Image reflection configuration for amsha-classifier-api.
 * Contract types moved here from core (extract-core-subsystems S6): the
 * answer-quality/tool-risk prediction family, the shadow-classifier SPIs,
 * {@code IntentShadowClassifier}, and the {@code ClassifierResult} family.
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
            ShadowClassifier.class
        },
        classNames = {
            "com.amsha.classifier.api.ClassifierResult$Opinion",
            "com.amsha.classifier.api.ClassifierResult$NoOpinion",
            "com.amsha.classifier.api.ClassifierResult$NoOpinionReason"
        })
public class ClassifierApiReflectionConfig {}
