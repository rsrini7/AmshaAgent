package com.amsha.core.classifier;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;

/** Shared failure-isolating asynchronous dispatcher for all Phase 2 shadow classifiers. */
public final class ClassifierShadowDispatcher {

    private ClassifierShadowDispatcher() {}

    public static <C, T> void observe(
            List<? extends ShadowClassifier<C, T>> classifiers,
            Executor executor,
            C context,
            Consumer<ClassifierResult<T>> resultConsumer) {
        Objects.requireNonNull(classifiers, "classifiers");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(resultConsumer, "resultConsumer");
        for (ShadowClassifier<C, T> classifier : classifiers) {
            try {
                executor.execute(() -> evaluate(classifier, context, resultConsumer));
            } catch (RejectedExecutionException e) {
                resultConsumer.accept(new ClassifierResult.NoOpinion<>(NoOpinionReason.OVERLOAD_REJECTED));
            }
        }
    }

    private static <C, T> void evaluate(
            ShadowClassifier<C, T> classifier, C context, Consumer<ClassifierResult<T>> resultConsumer) {
        ClassifierResult<T> result;
        try {
            result = Objects.requireNonNull(classifier.classify(context), "classifier result");
        } catch (RuntimeException | LinkageError e) {
            result = new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
        }
        resultConsumer.accept(result);
    }
}
