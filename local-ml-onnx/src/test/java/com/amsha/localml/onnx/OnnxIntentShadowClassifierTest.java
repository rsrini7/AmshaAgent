package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.onnxruntime.OrtEnvironment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class OnnxIntentShadowClassifierTest {

    @Test
    void killSwitchTakesPrecedenceOverMissingModel() {
        OnnxIntentShadowClassifier classifier = classifier(false, "", 1);

        ClassifierResult<?> result = classifier.classify("private input");

        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<?>) result).reason());
    }

    @Test
    void missingBundleOpensBreakerAfterConfiguredThreshold() {
        OnnxIntentShadowClassifier classifier = classifier(true, "missing-model", 1);

        ClassifierResult<?> missing = classifier.classify("private input");
        ClassifierResult<?> open = classifier.classify("private input");

        assertInstanceOf(ClassifierResult.NoOpinion.class, missing);
        assertInstanceOf(ClassifierResult.NoOpinion.class, open);
        assertEquals(NoOpinionReason.MISSING_MODEL, ((ClassifierResult.NoOpinion<?>) missing).reason());
        assertEquals(NoOpinionReason.CIRCUIT_OPEN, ((ClassifierResult.NoOpinion<?>) open).reason());
    }

    private static OnnxIntentShadowClassifier classifier(boolean enabled, String model, int threshold) {
        OnnxModelBundleRegistry registry = new OnnxModelBundleRegistry(new ObjectMapper(), false, Path.of("models"), 1);
        OnnxSessionLeaseManager leases = new OnnxSessionLeaseManager(
                Optional::<OrtEnvironment>empty,
                (environment, bundle) -> {
                    throw new UnsupportedOperationException();
                },
                Duration.ZERO);
        OnnxInferenceScheduler scheduler = new OnnxInferenceScheduler(1, 1, Duration.ZERO);
        OnnxShadowClassifierEngine<String> engine = new OnnxShadowClassifierEngine<>(
                registry,
                new OnnxRuntimeManager(),
                leases,
                scheduler,
                new OnnxTextClassifierRunner(),
                new OnnxClassifierCircuitBreaker(threshold, Duration.ofSeconds(30)),
                model,
                Duration.ofSeconds(1),
                1024,
                1,
                label -> label,
                Set.of());
        return new OnnxIntentShadowClassifier(enabled, engine::classify);
    }
}
