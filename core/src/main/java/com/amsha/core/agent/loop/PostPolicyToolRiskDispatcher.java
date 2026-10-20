package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.FailMode;
import com.amsha.classifier.api.PostPolicyToolContext;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.ReviewBand;
import com.amsha.classifier.api.ToolRiskAction;
import com.amsha.classifier.api.ToolRiskDecisionPolicy;
import com.amsha.classifier.api.ToolRiskDecisionPolicy.PolicyResolution;
import com.amsha.core.classifier.ClassifierFailureMetrics;
import com.amsha.core.classifier.DecisionTelemetry;
import com.amsha.core.classifier.OptionalClassifierBeanResolver;

import io.micrometer.core.instrument.MeterRegistry;

/** Orders and failure-isolates optional post-policy tool-risk interceptors. */
@ApplicationScoped
public class PostPolicyToolRiskDispatcher {

    public static final String DECISION_ID = "tool.risk.post_policy";
    public static final int SCHEMA_VERSION = 1;
    public static final QuestionKey<Choice> RISK_ACTION_KEY = QuestionKey.of("tool_risk", Choice.class);

    private static final String ERROR_METRIC = "amsha.classifier.tool_risk.active.interceptor.errors";

    private final List<PostPolicyToolRiskInterceptor> interceptors;
    private final ClassifierFailureMetrics failureMetrics;
    private final MeterRegistry meterRegistry;
    private final ReviewBand reviewBand;
    private final FailMode failMode;

    @Inject
    public PostPolicyToolRiskDispatcher(BeanManager beanManager, MeterRegistry meterRegistry) {
        this(
                OptionalClassifierBeanResolver.resolveOrdered(
                        beanManager, PostPolicyToolRiskInterceptor.class, PostPolicyToolRiskInterceptor::priority),
                meterRegistry,
                ToolRiskDecisionPolicy.DEFAULT_REVIEW_BAND,
                FailMode.FAIL_OPEN);
    }

    public PostPolicyToolRiskDispatcher(List<PostPolicyToolRiskInterceptor> interceptors, MeterRegistry meterRegistry) {
        this(interceptors, meterRegistry, ToolRiskDecisionPolicy.DEFAULT_REVIEW_BAND, FailMode.FAIL_OPEN);
    }

    public PostPolicyToolRiskDispatcher(
            List<PostPolicyToolRiskInterceptor> interceptors,
            MeterRegistry meterRegistry,
            ReviewBand reviewBand,
            FailMode failMode) {
        this.interceptors = OptionalClassifierBeanResolver.order(interceptors, PostPolicyToolRiskInterceptor::priority);
        this.failureMetrics = new ClassifierFailureMetrics(meterRegistry, ERROR_METRIC);
        this.meterRegistry = meterRegistry;
        this.reviewBand = reviewBand != null ? reviewBand : ToolRiskDecisionPolicy.DEFAULT_REVIEW_BAND;
        this.failMode = failMode != null ? failMode : FailMode.FAIL_OPEN;
    }

    public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
        return ToolRiskDecisionPolicy.toClassifierResult(evaluateResolution(context));
    }

    /** Evaluates interceptors and returns the typed policy resolution without reducing optional fields. */
    public PolicyResolution evaluateResolution(PostPolicyToolContext context) {
        Objects.requireNonNull(context, "context");
        PolicyResolution currentResolution = null;

        for (PostPolicyToolRiskInterceptor interceptor : interceptors) {
            DecisionResult decisionResult;
            try {
                decisionResult = Objects.requireNonNull(interceptor.evaluateDecision(context), "interceptor decision");
            } catch (RuntimeException | LinkageError error) {
                NoOpinionReason errorReason = failureMetrics.record(error);
                decisionResult = new DecisionResult.NoOpinion(DECISION_ID, SCHEMA_VERSION, errorReason);
            }

            if (!DECISION_ID.equals(decisionResult.decisionId()) || decisionResult.schemaVersion() != SCHEMA_VERSION) {
                decisionResult =
                        new DecisionResult.NoOpinion(DECISION_ID, SCHEMA_VERSION, NoOpinionReason.INFERENCE_ERROR);
            }

            currentResolution = ToolRiskDecisionPolicy.resolveCumulative(
                    currentResolution, decisionResult, RISK_ACTION_KEY, reviewBand, failMode);

            if (currentResolution.effectiveAction() == ToolRiskAction.BLOCK) {
                break;
            }
        }

        if (currentResolution == null) {
            DecisionResult emptyResult =
                    new DecisionResult.NoOpinion(DECISION_ID, SCHEMA_VERSION, NoOpinionReason.MISSING_MODEL);
            currentResolution =
                    ToolRiskDecisionPolicy.resolveCumulative(null, emptyResult, RISK_ACTION_KEY, reviewBand, failMode);
        }

        DecisionTelemetry.record(meterRegistry, currentResolution);
        return currentResolution;
    }
}
