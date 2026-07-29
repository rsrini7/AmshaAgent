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
import java.util.function.Function;

import com.amsha.core.agent.loop.AnswerQualityPrediction;
import com.amsha.core.agent.loop.AnswerQualityShadowClassifier;
import com.amsha.core.agent.loop.AnswerQualityShadowContext;
import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/** Disabled-by-default ONNX answer-quality classifier for accepted-answer shadow observation. */
@ApplicationScoped
@Unremovable
public class OnnxAnswerQualityShadowClassifier implements AnswerQualityShadowClassifier {

    private final boolean enabled;
    private final ObjectMapper objectMapper;
    private final Function<String, ClassifierResult<AnswerQualityPrediction>> classifier;

    @Inject
    public OnnxAnswerQualityShadowClassifier(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "amsha.onnx.classifier.answer-quality.enabled", defaultValue = "false")
                    boolean enabled,
            @ConfigProperty(name = "amsha.onnx.classifier.answer-quality.model") Optional<String> model,
            @ConfigProperty(name = "amsha.onnx.classifier.answer-quality.timeout-seconds", defaultValue = "2")
                    long timeoutSeconds,
            @ConfigProperty(name = "amsha.onnx.classifier.circuit.failure-threshold", defaultValue = "3")
                    int failureThreshold,
            @ConfigProperty(name = "amsha.onnx.classifier.circuit.cooldown-seconds", defaultValue = "30")
                    long cooldownSeconds,
            @ConfigProperty(name = "amsha.onnx.max-input-bytes", defaultValue = "262144") long maxInputBytes,
            @ConfigProperty(name = "amsha.onnx.warmup.max-probes", defaultValue = "5") int warmupMaxProbes) {
        this(
                enabled,
                objectMapper,
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
                        OnnxAnswerQualityShadowClassifier::parseLabel,
                        Set.of(AnswerQualityPrediction.values()))::classify);
    }

    OnnxAnswerQualityShadowClassifier(
            boolean enabled,
            ObjectMapper objectMapper,
            Function<String, ClassifierResult<AnswerQualityPrediction>> classifier) {
        this.enabled = enabled;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    @Override
    public ClassifierResult<AnswerQualityPrediction> classify(AnswerQualityShadowContext context) {
        Objects.requireNonNull(context, "context");
        if (!enabled) return new ClassifierResult.NoOpinion<>(NoOpinionReason.DISABLED);
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("classifier", "answer_quality");
            input.put("agent_role", context.agentRole());
            input.put("goal", context.goal());
            input.put("answer", context.answer());
            input.put("tool_invoked_in_turn", context.toolInvokedInTurn());
            return classifier.apply(objectMapper.writeValueAsString(input));
        } catch (JsonProcessingException | RuntimeException e) {
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
        }
    }

    static AnswerQualityPrediction parseLabel(String label) {
        return AnswerQualityPrediction.valueOf(normalizeLabel(label));
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
