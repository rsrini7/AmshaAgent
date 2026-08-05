package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;

import com.amsha.core.classifier.ClassifierFailureMetrics;
import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;
import com.amsha.core.classifier.OptionalClassifierBeanResolver;

import io.micrometer.core.instrument.MeterRegistry;

/** Orders and failure-isolates optional answer-quality interceptors. */
@ApplicationScoped
public class AnswerQualityDispatcher {

    private static final String ERROR_METRIC = "amsha.classifier.answer_quality.active.interceptor.errors";

    private final List<AnswerQualityInterceptor> interceptors;
    private final ClassifierFailureMetrics failureMetrics;

    @Inject
    public AnswerQualityDispatcher(BeanManager beanManager, MeterRegistry meterRegistry) {
        this(
                OptionalClassifierBeanResolver.resolveOrdered(
                        beanManager, AnswerQualityInterceptor.class, AnswerQualityInterceptor::priority),
                meterRegistry);
    }

    AnswerQualityDispatcher(List<AnswerQualityInterceptor> interceptors, MeterRegistry meterRegistry) {
        this.interceptors = OptionalClassifierBeanResolver.order(interceptors, AnswerQualityInterceptor::priority);
        this.failureMetrics = new ClassifierFailureMetrics(meterRegistry, ERROR_METRIC);
    }

    public ClassifierResult<AnswerQualityAction> evaluate(AnswerQualityContext context) {
        Objects.requireNonNull(context, "context");
        NoOpinionReason fallbackReason = NoOpinionReason.MISSING_MODEL;
        for (AnswerQualityInterceptor interceptor : interceptors) {
            ClassifierResult<AnswerQualityAction> result;
            try {
                result = Objects.requireNonNull(interceptor.evaluate(context), "interceptor result");
            } catch (RuntimeException | LinkageError error) {
                fallbackReason = failureMetrics.record(error);
                continue;
            }
            switch (result) {
                case ClassifierResult.Opinion<AnswerQualityAction> opinion -> {
                    if (opinion.value() == AnswerQualityAction.REQUEST_RETRY) return opinion;
                }
                case ClassifierResult.NoOpinion<AnswerQualityAction> noOpinion -> fallbackReason = noOpinion.reason();
            }
        }
        return new ClassifierResult.NoOpinion<>(fallbackReason);
    }
}
