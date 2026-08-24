package com.amsha.localml.onnx;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.PostPolicyToolContext;
import com.amsha.classifier.api.ToolRiskAction;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.core.agent.loop.PostPolicyToolRiskInterceptor;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Classifier;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Lease;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Registration;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Startup;

/**
 * Phase 3 active ONNX interceptor for post-policy tool risk classification.
 *
 * <p>Executes active ONNX classification only when explicitly enabled by runtime controls
 * and backed by a ready model implementation. Returns {@link ClassifierResult.NoOpinion}
 * otherwise, preserving the authoritative deterministic guard path.
 */
@ApplicationScoped
@Startup
@Unremovable
public class OnnxPostPolicyToolRiskInterceptor implements PostPolicyToolRiskInterceptor {

    OnnxClassifierRuntimeControl runtimeControl;
    OnnxToolRiskShadowClassifier shadowClassifier;
    Registration registration;

    /** Required for CDI proxying. */
    OnnxPostPolicyToolRiskInterceptor() {}

    @Inject
    public OnnxPostPolicyToolRiskInterceptor(
            OnnxClassifierRuntimeControl runtimeControl, OnnxToolRiskShadowClassifier shadowClassifier) {
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
    public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
        Optional<Lease> leaseOpt = runtimeControl.tryAcquire(Classifier.TOOL_RISK);
        if (leaseOpt.isEmpty()) {
            return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.DISABLED);
        }

        try (var _ = leaseOpt.get()) {
            ToolRiskShadowContext shadowContext = new ToolRiskShadowContext(
                    context.agentRole(),
                    context.autonomyLevel(),
                    context.toolName(),
                    context.parameters(),
                    context.goal());

            ClassifierResult<ToolRiskPrediction> shadowResult = shadowClassifier.classifyUnderLease(shadowContext);
            return switch (shadowResult) {
                case ClassifierResult.NoOpinion<ToolRiskPrediction>(var reason) ->
                    new ClassifierResult.NoOpinion<>(reason);
                case ClassifierResult.Opinion<ToolRiskPrediction>(var value, var confidence, var ruleId) ->
                    switch (value) {
                        case REQUIRE_APPROVAL ->
                            new ClassifierResult.Opinion<>(ToolRiskAction.REQUIRE_APPROVAL, confidence, ruleId);
                        case BLOCK -> new ClassifierResult.Opinion<>(ToolRiskAction.BLOCK, confidence, ruleId);
                        default -> new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.LOW_CONFIDENCE);
                    };
            };
        } catch (RuntimeException _) {
            return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.INFERENCE_ERROR);
        }
    }
}
