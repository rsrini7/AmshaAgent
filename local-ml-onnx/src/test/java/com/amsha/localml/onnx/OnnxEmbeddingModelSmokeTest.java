package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.embedding.EmbeddingModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.amsha.rag.EmbeddingModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Tag("model-smoke")
class OnnxEmbeddingModelSmokeTest {

    private OnnxRuntimeManager runtimeManager;
    private OnnxSessionLeaseManager sessionLeaseManager;
    private OnnxInferenceScheduler scheduler;

    @AfterEach
    void closeRuntime() {
        if (scheduler != null) scheduler.shutdown();
        if (sessionLeaseManager != null) sessionLeaseManager.shutdown();
        if (runtimeManager != null) runtimeManager.close();
    }

    @Test
    void runsPinnedMpnetBundleWhenInstalled() throws Exception {
        Path modelRoot = Path.of("data/models").toAbsolutePath();
        Path bundlePath = modelRoot.resolve("all-mpnet-base-v2-e8c3b32");
        Assumptions.assumeTrue(
                Files.isRegularFile(bundlePath.resolve("model.onnx")), "run mise run models:install-mpnet");

        runtimeManager = new OnnxRuntimeManager();
        runtimeManager.enabled = true;
        runtimeManager.initialize();
        assertTrue(runtimeManager.isReady());
        OnnxModelBundleRegistry registry =
                new OnnxModelBundleRegistry(new ObjectMapper(), true, modelRoot, 1_073_741_824L);
        VerifiedOnnxModelBundle bundle = registry.loadVerified("all-mpnet-base-v2-e8c3b32");
        sessionLeaseManager =
                new OnnxSessionLeaseManager(runtimeManager, new OnnxTokenizerFactory(), Duration.ofSeconds(5));
        scheduler = new OnnxInferenceScheduler(1, 1, Duration.ofSeconds(5));
        scheduler.initialize();
        OnnxInferenceMetrics metrics = new OnnxInferenceMetrics(new SimpleMeterRegistry());
        metrics.initialize();

        EmbeddingModel model = new OnnxEmbeddingProviderFactory(
                        registry,
                        runtimeManager,
                        sessionLeaseManager,
                        scheduler,
                        new OnnxEmbeddingRunner(),
                        metrics,
                        262_144,
                        1_048_576,
                        32,
                        true,
                        2,
                        Duration.ofSeconds(5))
                .create(new EmbeddingModelConfig(
                        "onnx", bundle.manifest().bundleId(), "", Optional.empty(), Duration.ofSeconds(10)));
        List<float[]> vectors = List.of(
                model.embed("Amsha runs local ONNX embeddings.").content().vector());

        assertEquals(1, vectors.size());
        assertEquals(768, vectors.getFirst().length);
        double normSquared = 0;
        for (float value : vectors.getFirst()) {
            assertTrue(Float.isFinite(value));
            normSquared += value * value;
        }
        assertEquals(1.0, Math.sqrt(normSquared), 0.0001);
    }
}
