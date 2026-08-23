package com.amsha.classifier.api;

/** Optional intent classifier that observes input but never chooses the active route. */
public interface IntentShadowClassifier extends ShadowClassifier<String, String> {

    /** Returns a canonical role opinion or an explicit operational no-opinion result. */
    ClassifierResult<String> classify(String userMessage);
}
