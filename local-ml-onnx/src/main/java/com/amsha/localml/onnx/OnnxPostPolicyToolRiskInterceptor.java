package com.amsha.localml.onnx;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.core.agent.loop.PostPolicyToolContext;
import com.amsha.core.agent.loop.PostPolicyToolRiskInterceptor;
import com.amsha.core.agent.loop.ToolRiskAction;
import com.amsha.core.agent.loop.ToolRiskPrediction;
import com.amsha.core.agent.loop.ToolRiskShadowContext;
import com.amsha.core.classifier.ClassifierResult;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Classifier;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Lease;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Registration;

import io.quarkus.arc.Unremovable;

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
        registration = runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK);
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

        try (Lease lease = leaseOpt.get()) {
            ToolRiskShadowContext shadowContext = new ToolRiskShadowContext(
                    context.agentRole(),
                    context.autonomyLevel(),
                    context.toolName(),
                    context.parameters(),
                    context.goal());

            ClassifierResult<ToolRiskPrediction> shadowResult = shadowClassifier.classify(shadowContext);
            if (shadowResult instanceof ClassifierResult.NoOpinion<ToolRiskPrediction> noOpinion) {
                return new ClassifierResult.NoOpinion<>(noOpinion.reason());
            } else if (shadowResult instanceof ClassifierResult.Opinion<ToolRiskPrediction> opinion) {
                if (opinion.value() == ToolRiskPrediction.REQUIRE_APPROVAL) {
                    return new ClassifierResult.Opinion<>(
                            ToolRiskAction.REQUIRE_APPROVAL, opinion.confidence(), opinion.ruleId());
                } else if (opinion.value() == ToolRiskPrediction.BLOCK) {
                    return new ClassifierResult.Opinion<>(ToolRiskAction.BLOCK, opinion.confidence(), opinion.ruleId());
                } else {
                    return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.LOW_CONFIDENCE);
                }
            }
            return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.INFERENCE_ERROR);
        } catch (RuntimeException _) {
            return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.INFERENCE_ERROR);
        }
    }
}
