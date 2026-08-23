package com.amsha.localml.onnx;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.IntentShadowClassifier;
import com.amsha.core.agent.role.AgentRoleEnum;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/** Disabled-by-default ONNX intent classifier that can only emit shadow observations. */
@ApplicationScoped
@Unremovable
public class OnnxIntentShadowClassifier implements IntentShadowClassifier {

    private final boolean enabled;
    private final Function<String, ClassifierResult<String>> classifier;

    @Inject
    public OnnxIntentShadowClassifier(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            @ConfigProperty(name = "amsha.onnx.classifier.intent.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "amsha.onnx.classifier.intent.model") Optional<String> model,
            @ConfigProperty(name = "amsha.onnx.classifier.intent.timeout-seconds", defaultValue = "2")
                    long timeoutSeconds,
            @ConfigProperty(name = "amsha.onnx.classifier.circuit.failure-threshold", defaultValue = "3")
                    int failureThreshold,
            @ConfigProperty(name = "amsha.onnx.classifier.circuit.cooldown-seconds", defaultValue = "30")
                    long cooldownSeconds,
            @ConfigProperty(name = "amsha.onnx.max-input-bytes", defaultValue = "262144") long maxInputBytes,
            @ConfigProperty(name = "amsha.onnx.warmup.max-probes", defaultValue = "5") int warmupMaxProbes) {
        this(
                enabled,
                new OnnxShadowClassifierEngine<>(
                        bundleRegistry,
                        runtimeManager,
                        sessionLeaseManager,
                        scheduler,
                        new OnnxTextClassifierRunner(),
                        new OnnxClassifierCircuitBreaker(
                                failureThreshold,
                                Duration.ofSeconds(requirePositive(cooldownSeconds, "cooldownSeconds"))),
                        Objects.requireNonNull(model, "model").orElse(""),
                        Duration.ofSeconds(requirePositive(timeoutSeconds, "timeoutSeconds")),
                        maxInputBytes,
                        warmupMaxProbes,
                        label -> AgentRoleEnum.fromString(label).toScopedName(),
                        Set.of())::classify);
    }

    OnnxIntentShadowClassifier(boolean enabled, Function<String, ClassifierResult<String>> classifier) {
        this.enabled = enabled;
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    @Override
    public ClassifierResult<String> classify(String userMessage) {
        Objects.requireNonNull(userMessage, "userMessage");
        if (!enabled) return new ClassifierResult.NoOpinion<>(NoOpinionReason.DISABLED);
        return classifier.apply(userMessage);
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
