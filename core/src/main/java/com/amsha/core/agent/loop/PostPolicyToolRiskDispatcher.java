package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.PostPolicyToolContext;
import com.amsha.classifier.api.ToolRiskAction;
import com.amsha.core.classifier.ClassifierFailureMetrics;
import com.amsha.core.classifier.OptionalClassifierBeanResolver;

import io.micrometer.core.instrument.MeterRegistry;

/** Orders and failure-isolates optional post-policy tool-risk interceptors. */
@ApplicationScoped
public class PostPolicyToolRiskDispatcher {

    private static final String ERROR_METRIC = "amsha.classifier.tool_risk.active.interceptor.errors";

    private final List<PostPolicyToolRiskInterceptor> interceptors;
    private final ClassifierFailureMetrics failureMetrics;

    @Inject
    public PostPolicyToolRiskDispatcher(BeanManager beanManager, MeterRegistry meterRegistry) {
        this(
                OptionalClassifierBeanResolver.resolveOrdered(
                        beanManager, PostPolicyToolRiskInterceptor.class, PostPolicyToolRiskInterceptor::priority),
                meterRegistry);
    }

    PostPolicyToolRiskDispatcher(List<PostPolicyToolRiskInterceptor> interceptors, MeterRegistry meterRegistry) {
        this.interceptors = OptionalClassifierBeanResolver.order(interceptors, PostPolicyToolRiskInterceptor::priority);
        this.failureMetrics = new ClassifierFailureMetrics(meterRegistry, ERROR_METRIC);
    }

    public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
        Objects.requireNonNull(context, "context");
        ClassifierResult.Opinion<ToolRiskAction> strongest = null;
        NoOpinionReason fallbackReason = NoOpinionReason.MISSING_MODEL;

        for (PostPolicyToolRiskInterceptor interceptor : interceptors) {
            ClassifierResult<ToolRiskAction> result;
            try {
                result = Objects.requireNonNull(interceptor.evaluate(context), "interceptor result");
            } catch (RuntimeException | LinkageError error) {
                fallbackReason = failureMetrics.record(error);
                continue;
            }
            switch (result) {
                case ClassifierResult.Opinion<ToolRiskAction> opinion -> {
                    if (strongest == null || severity(opinion.value()) > severity(strongest.value())) {
                        strongest = opinion;
                    }
                }
                case ClassifierResult.NoOpinion<ToolRiskAction> noOpinion -> fallbackReason = noOpinion.reason();
            }
            if (strongest != null && strongest.value() == ToolRiskAction.BLOCK) break;
        }
        return strongest != null ? strongest : new ClassifierResult.NoOpinion<>(fallbackReason);
    }

    private static int severity(ToolRiskAction action) {
        return switch (action) {
            case REQUIRE_APPROVAL -> 1;
            case BLOCK -> 2;
        };
    }
}
