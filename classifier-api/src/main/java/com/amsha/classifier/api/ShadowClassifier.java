package com.amsha.classifier.api;

/** Generic contract for classifiers whose opinions are observational and non-authoritative. */
public interface ShadowClassifier<C, T> {

    ClassifierResult<T> classify(C context);
}
