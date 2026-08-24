package com.amsha.localml.onnx;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.classifier.api.AnswerQualityAction;
import com.amsha.classifier.api.AnswerQualityContext;
import com.amsha.classifier.api.AnswerQualityPrediction;
import com.amsha.classifier.api.AnswerQualityShadowContext;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.core.agent.loop.AnswerQualityInterceptor;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Classifier;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Lease;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Registration;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Startup;

/**
 * Phase 3 active ONNX interceptor for post-policy answer quality classification.
 *
 * <p>Executes active ONNX classification only when explicitly enabled by runtime controls
 * and backed by a ready model implementation. Returns {@link ClassifierResult.NoOpinion}
 * otherwise, preserving the authoritative deterministic guard path.
 */
@ApplicationScoped
@Startup
@Unremovable
public class OnnxAnswerQualityInterceptor implements AnswerQualityInterceptor {

    OnnxClassifierRuntimeControl runtimeControl;
    OnnxAnswerQualityShadowClassifier shadowClassifier;
    Registration registration;

    /** Required for CDI proxying. */
    OnnxAnswerQualityInterceptor() {}

    @Inject
    public OnnxAnswerQualityInterceptor(
            OnnxClassifierRuntimeControl runtimeControl, OnnxAnswerQualityShadowClassifier shadowClassifier) {
        this.runtimeControl = runtimeControl;
        this.shadowClassifier = shadowClassifier;
    }

    @PostConstruct
    void init() {
        registration = shadowClassifier.prepareAndRegister(runtimeControl).orElse(null);
    }

    @PreDestroy
    void destroy() {
        if (registration != null) {
            registration.close();
        }
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public ClassifierResult<AnswerQualityAction> evaluate(AnswerQualityContext context) {
        Optional<Lease> leaseOpt = runtimeControl.tryAcquire(Classifier.ANSWER_QUALITY);
        if (leaseOpt.isEmpty()) {
            return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.DISABLED);
        }

        try (var _ = leaseOpt.get()) {
            AnswerQualityShadowContext shadowContext = new AnswerQualityShadowContext(
                    context.agentRole(), context.goal(), context.answer(), context.toolInvokedInTurn());

            ClassifierResult<AnswerQualityPrediction> shadowResult = shadowClassifier.classifyUnderLease(shadowContext);
            return switch (shadowResult) {
                case ClassifierResult.NoOpinion<AnswerQualityPrediction>(var reason) ->
                    new ClassifierResult.NoOpinion<>(reason);
                case ClassifierResult.Opinion<AnswerQualityPrediction>(var value, var confidence, var ruleId) ->
                    switch (value) {
                        case RETRY_RECOMMENDED ->
                            new ClassifierResult.Opinion<>(AnswerQualityAction.REQUEST_RETRY, confidence, ruleId);
                        default -> new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.LOW_CONFIDENCE);
                    };
            };
        } catch (RuntimeException _) {
            return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.INFERENCE_ERROR);
        }
    }
}
