package com.amsha.core.routing;

import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ShadowClassifier;

/** Optional Phase 2 intent classifier that observes input but never chooses the active route. */
public interface IntentShadowClassifier extends ShadowClassifier<String, String> {

    /** Returns a canonical role opinion or an explicit operational no-opinion result. */
    ClassifierResult<String> classify(String userMessage);
}
