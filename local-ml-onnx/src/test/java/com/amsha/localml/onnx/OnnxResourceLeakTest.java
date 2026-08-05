package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.embedding.EmbeddingModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.rag.EmbeddingModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Tag("model-smoke")
class OnnxResourceLeakTest {

    private static final String BUNDLE_ID = "all-mpnet-base-v2-e8c3b32";
    private static final long MEBIBYTE = 1024L * 1024L;

    @Test
    void repeatedRuntimeSessionInferenceAndShutdownHasBoundedResidentGrowth() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("amsha.onnx.release-evidence"),
                "the native-memory lifecycle gate runs only in the release-evidence job");
        Path modelRoot = modelRoot();
        assertTrue(Files.isRegularFile(modelRoot.resolve(BUNDLE_ID).resolve("model.onnx")), "install MPNet bundle");

        int lifecycleCount = Integer.getInteger("amsha.onnx.leak.lifecycle-count", 4);
        int inferencesPerLifecycle = Integer.getInteger("amsha.onnx.leak.inferences-per-lifecycle", 50);
        long maxGrowthBytes = Long.getLong("amsha.onnx.leak.max-rss-growth-mb", 64L) * MEBIBYTE;
        assertTrue(lifecycleCount >= 3, "leak evidence requires at least three measured lifecycles");
        assertTrue(inferencesPerLifecycle >= 20, "leak evidence requires repeated inference in every lifecycle");

        runLifecycle(modelRoot, 10);
        settleForResidentSample();
        long baselineRss = residentSetBytes();
        List<Long> samples = new ArrayList<>();
        for (int lifecycle = 0; lifecycle < lifecycleCount; lifecycle++) {
            runLifecycle(modelRoot, inferencesPerLifecycle);
            settleForResidentSample();
            samples.add(residentSetBytes());
        }

        long peakGrowth = samples.stream()
                .mapToLong(sample -> Math.max(0L, sample - baselineRss))
                .max()
                .orElseThrow();
        long finalGrowth = Math.max(0L, samples.getLast() - baselineRss);
        assertTrue(
                peakGrowth <= maxGrowthBytes,
                () -> "native RSS peak growth=" + peakGrowth + " bytes; baseline=" + baselineRss + "; samples="
                        + samples);
        assertTrue(
                finalGrowth <= maxGrowthBytes,
                () -> "native RSS final growth=" + finalGrowth + " bytes; baseline=" + baselineRss + "; samples="
                        + samples);
    }

    private static void runLifecycle(Path modelRoot, int inferenceCount) throws Exception {
        OnnxRuntimeManager runtimeManager = new OnnxRuntimeManager();
        OnnxSessionLeaseManager sessionLeaseManager = null;
        OnnxInferenceScheduler scheduler = null;
        try {
            runtimeManager.enabled = true;
            runtimeManager.initialize();
            assertTrue(runtimeManager.isReady());

            ObjectMapper objectMapper = new ObjectMapper();
            OnnxModelBundleRegistry registry =
                    new OnnxModelBundleRegistry(objectMapper, true, modelRoot, 1_073_741_824L);
            VerifiedOnnxModelBundle bundle = registry.loadVerified(BUNDLE_ID);
            sessionLeaseManager =
                    new OnnxSessionLeaseManager(runtimeManager, new OnnxTokenizerFactory(), Duration.ofSeconds(5));
            scheduler = new OnnxInferenceScheduler(1, 2, Duration.ofSeconds(10));
            scheduler.initialize();
            OnnxInferenceMetrics metrics = new OnnxInferenceMetrics(new SimpleMeterRegistry());
            metrics.initialize();
            EmbeddingModel model = new OnnxEmbeddingProviderFactory(
                            registry,
                            runtimeManager,
                            sessionLeaseManager,
                            scheduler,
                            new OnnxEmbeddingRunner(metrics),
                            metrics,
                            262_144,
                            1_048_576,
                            32,
                            false,
                            1,
                            Duration.ofSeconds(10))
                    .create(new EmbeddingModelConfig(
                            "onnx", bundle.manifest().bundleId(), "", Optional.empty(), Duration.ofSeconds(15)));

            for (int iteration = 0; iteration < inferenceCount; iteration++) {
                float[] vector = model.embed("Native resource lifecycle probe " + iteration)
                        .content()
                        .vector();
                assertEquals(768, vector.length);
            }
        } finally {
            if (scheduler != null) scheduler.shutdown();
            if (sessionLeaseManager != null) sessionLeaseManager.shutdown();
            runtimeManager.close();
        }
    }

    private static long residentSetBytes() throws Exception {
        Path procStatus = Path.of("/proc/self/status");
        if (Files.isRegularFile(procStatus)) {
            for (String line : Files.readAllLines(procStatus, StandardCharsets.US_ASCII)) {
                if (line.startsWith("VmRSS:")) {
                    String kibibytes = firstWhitespaceDelimitedToken(
                            line.substring("VmRSS:".length()).trim());
                    return Long.parseLong(kibibytes) * 1024L;
                }
            }
            throw new IOException("VmRSS is missing from /proc/self/status");
        }

        Process process = new ProcessBuilder(
                        "ps",
                        "-o",
                        "rss=",
                        "-p",
                        Long.toString(ProcessHandle.current().pid()))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
        if (process.waitFor() != 0 || output.isEmpty()) {
            throw new IOException("unable to obtain process RSS: " + output);
        }
        return Long.parseLong(firstWhitespaceDelimitedToken(output)) * 1024L;
    }

    private static String firstWhitespaceDelimitedToken(String value) {
        int end = 0;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) end++;
        return value.substring(0, end);
    }

    private static void settleForResidentSample() throws InterruptedException {
        System.gc();
        Thread.sleep(250);
    }

    private static Path modelRoot() {
        String configured = System.getProperty("amsha.onnx.model-root");
        if (configured != null && !configured.isBlank())
            return Path.of(configured).toAbsolutePath();
        return (Files.isDirectory(Path.of("data/models")) ? Path.of("data/models") : Path.of("../data/models"))
                .toAbsolutePath();
    }
}
