package com.amsha.classifier.api;

/** Bounded answer-quality prediction; it never requests an active retry. */
public enum AnswerQualityPrediction {
    ACCEPTABLE,
    RETRY_RECOMMENDED
}
