package com.amsha.core.agent.loop;

import com.amsha.core.classifier.ShadowClassifier;

/** Optional answer-quality classifier that observes only guard-accepted candidates. */
public interface AnswerQualityShadowClassifier
        extends ShadowClassifier<AnswerQualityShadowContext, AnswerQualityPrediction> {}
