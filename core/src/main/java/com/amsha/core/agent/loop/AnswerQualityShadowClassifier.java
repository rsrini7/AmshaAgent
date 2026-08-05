package com.amsha.core.agent.loop;

import com.amsha.core.classifier.ShadowClassifier;

/**
 * Optional in-process answer-quality classifier that observes only guard-accepted candidates.
 * Implementations must not perform network I/O or log, persist, export, metric-label, or retain
 * context payloads.
 */
public interface AnswerQualityShadowClassifier
        extends ShadowClassifier<AnswerQualityShadowContext, AnswerQualityPrediction> {}
