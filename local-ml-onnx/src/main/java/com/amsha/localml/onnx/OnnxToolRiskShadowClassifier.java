package com.amsha.localml.onnx;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.amsha.core.agent.loop.ToolRiskPrediction;
import com.amsha.core.agent.loop.ToolRiskShadowClassifier;
import com.amsha.core.agent.loop.ToolRiskShadowContext;
import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/** Disabled-by-default ONNX tool-risk classifier for provisional-allow shadow observation. */
@ApplicationScoped
@Unremovable
public class OnnxToolRiskShadowClassifier implements ToolRiskShadowClassifier {

    private final boolean enabled;
    private final ObjectMapper objectMapper;
    private final Function<String, ClassifierResult<ToolRiskPrediction>> classifier;
    private final BooleanSupplier readinessCheck;

    @Inject
    public OnnxToolRiskShadowClassifier(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "amsha.onnx.classifier.tool-risk.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "amsha.onnx.classifier.tool-risk.model") Optional<String> model,
            @ConfigProperty(name = "amsha.onnx.classifier.tool-risk.timeout-seconds", defaultValue = "2")
                    long timeoutSeconds,
            @ConfigProperty(name = "amsha.onnx.classifier.circuit.failure-threshold", defaultValue = "3")
                    int failureThreshold,
            @ConfigProperty(name = "amsha.onnx.classifier.circuit.cooldown-seconds", defaultValue = "30")
                    long cooldownSeconds,
            @ConfigProperty(name = "amsha.onnx.max-input-bytes", defaultValue = "262144") long maxInputBytes,
            @ConfigProperty(name = "amsha.onnx.warmup.max-probes", defaultValue = "5") int warmupMaxProbes) {
        OnnxShadowClassifierEngine<ToolRiskPrediction> engine = new OnnxShadowClassifierEngine<>(
                bundleRegistry,
                runtimeManager,
                sessionLeaseManager,
                scheduler,
                new OnnxTextClassifierRunner(),
                new OnnxClassifierCircuitBreaker(
                        failureThreshold, Duration.ofSeconds(requirePositive(cooldownSeconds, "cooldownSeconds"))),
                Objects.requireNonNull(model, "model").orElse(""),
                Duration.ofSeconds(requirePositive(timeoutSeconds, "timeoutSeconds")),
                maxInputBytes,
                warmupMaxProbes,
                OnnxToolRiskShadowClassifier::parseLabel,
                Set.of(ToolRiskPrediction.values()));
        this.enabled = enabled;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.classifier = engine::classify;
        this.readinessCheck = engine::ensureReady;
    }

    OnnxToolRiskShadowClassifier(
            boolean enabled,
            ObjectMapper objectMapper,
            Function<String, ClassifierResult<ToolRiskPrediction>> classifier) {
        this(enabled, objectMapper, classifier, () -> true);
    }

    OnnxToolRiskShadowClassifier(
            boolean enabled,
            ObjectMapper objectMapper,
            Function<String, ClassifierResult<ToolRiskPrediction>> classifier,
            BooleanSupplier readinessCheck) {
        this.enabled = enabled;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.readinessCheck = Objects.requireNonNull(readinessCheck, "readinessCheck");
    }

    @Override
    public ClassifierResult<ToolRiskPrediction> classify(ToolRiskShadowContext context) {
        Objects.requireNonNull(context, "context");
        if (!enabled) return new ClassifierResult.NoOpinion<>(NoOpinionReason.DISABLED);
        return classifyUnderLease(context);
    }

    /**
     * Active Phase-3 path: classify without re-checking the Phase-2 boot {@code enabled} flag.
     * Callers must hold a runtime lease from {@link OnnxClassifierRuntimeControl}.
     */
    public ClassifierResult<ToolRiskPrediction> classifyUnderLease(ToolRiskShadowContext context) {
        Objects.requireNonNull(context, "context");
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("classifier", "tool_risk");
            input.put("agent_role", context.agentRole());
            input.put("autonomy", context.autonomyLevel().name());
            input.put("tool_name", context.toolName());
            input.put("goal", context.goal());
            input.put("parameters", new TreeMap<>(context.parameters()));
            return classifier.apply(objectMapper.writeValueAsString(input));
        } catch (JsonProcessingException | RuntimeException e) {
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
        }
    }

    /**
     * Validates and warms the model; registers ready only after success.
     * Fixture classifiers treat readiness as always true.
     */
    public Optional<OnnxClassifierRuntimeControl.Registration> prepareAndRegister(
            OnnxClassifierRuntimeControl runtimeControl) {
        Objects.requireNonNull(runtimeControl, "runtimeControl");
        if (!readinessCheck.getAsBoolean()) {
            return Optional.empty();
        }
        return Optional.of(
                runtimeControl.registerReadyImplementation(OnnxClassifierRuntimeControl.Classifier.TOOL_RISK));
    }

    static ToolRiskPrediction parseLabel(String label) {
        return ToolRiskPrediction.valueOf(normalizeLabel(label));
    }

    private static String normalizeLabel(String label) {
        return Objects.requireNonNull(label, "label")
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
