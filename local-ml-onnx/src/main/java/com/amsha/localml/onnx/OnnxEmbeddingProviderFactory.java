package com.amsha.localml.onnx;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.rag.EmbeddingModelConfig;
import com.amsha.rag.EmbeddingProviderFactory;
import com.amsha.rag.EmbeddingSpaceDescriptor;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.Context;

/** Opt-in RAG embedding provider backed exclusively by verified local ONNX bundles. */
@ApplicationScoped
public class OnnxEmbeddingProviderFactory implements EmbeddingProviderFactory {

    private final OnnxModelBundleRegistry bundleRegistry;
    private final OnnxRuntimeManager runtimeManager;
    private final OnnxSessionLeaseManager sessionLeaseManager;
    private final OnnxInferenceScheduler scheduler;
    private final OnnxEmbeddingRunner runner;
    private final OnnxInferenceMetrics metrics;
    private final long maxInputBytes;
    private final long maxBatchInputBytes;
    private final int maxBatchSize;
    private final boolean warmupEnabled;
    private final int warmupMaxProbes;
    private final Duration warmupTimeout;

    @Inject
    public OnnxEmbeddingProviderFactory(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            OnnxInferenceMetrics metrics,
            @ConfigProperty(name = "amsha.onnx.max-input-bytes", defaultValue = "262144") long maxInputBytes,
            @ConfigProperty(name = "amsha.onnx.max-batch-input-bytes", defaultValue = "1048576")
                    long maxBatchInputBytes,
            @ConfigProperty(name = "amsha.onnx.max-batch-size", defaultValue = "32") int maxBatchSize,
            @ConfigProperty(name = "amsha.onnx.warmup.enabled", defaultValue = "true") boolean warmupEnabled,
            @ConfigProperty(name = "amsha.onnx.warmup.max-probes", defaultValue = "5") int warmupMaxProbes,
            @ConfigProperty(name = "amsha.onnx.warmup.timeout", defaultValue = "PT3S") Duration warmupTimeout) {
        this(
                bundleRegistry,
                runtimeManager,
                sessionLeaseManager,
                scheduler,
                new OnnxEmbeddingRunner(metrics),
                metrics,
                maxInputBytes,
                maxBatchInputBytes,
                maxBatchSize,
                warmupEnabled,
                warmupMaxProbes,
                warmupTimeout);
    }

    OnnxEmbeddingProviderFactory(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            OnnxEmbeddingRunner runner,
            OnnxInferenceMetrics metrics,
            long maxInputBytes,
            long maxBatchInputBytes,
            int maxBatchSize) {
        this(
                bundleRegistry,
                runtimeManager,
                sessionLeaseManager,
                scheduler,
                runner,
                metrics,
                maxInputBytes,
                maxBatchInputBytes,
                maxBatchSize,
                false,
                1,
                Duration.ofSeconds(1));
    }

    OnnxEmbeddingProviderFactory(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            OnnxEmbeddingRunner runner,
            OnnxInferenceMetrics metrics,
            long maxInputBytes,
            long maxBatchInputBytes,
            int maxBatchSize,
            boolean warmupEnabled,
            int warmupMaxProbes,
            Duration warmupTimeout) {
        this.bundleRegistry = Objects.requireNonNull(bundleRegistry, "bundleRegistry");
        this.runtimeManager = Objects.requireNonNull(runtimeManager, "runtimeManager");
        this.sessionLeaseManager = Objects.requireNonNull(sessionLeaseManager, "sessionLeaseManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.maxInputBytes = requirePositive(maxInputBytes, "maxInputBytes");
        this.maxBatchInputBytes = requirePositive(maxBatchInputBytes, "maxBatchInputBytes");
        this.maxBatchSize = requirePositive(maxBatchSize, "maxBatchSize");
        this.warmupEnabled = warmupEnabled;
        this.warmupMaxProbes = requirePositive(warmupMaxProbes, "warmupMaxProbes");
        this.warmupTimeout = requirePositive(warmupTimeout, "warmupTimeout");
    }

    @Override
    public String providerId() {
        return "onnx";
    }

    @Override
    public EmbeddingModel create(EmbeddingModelConfig config) {
        if (!runtimeManager.isReady()) {
            throw new IllegalStateException("ONNX Runtime is unavailable: " + runtimeManager.unavailableReason());
        }
        VerifiedOnnxModelBundle bundle = loadEmbeddingBundle(config);
        OnnxEmbeddingBundleSpec spec = bundle.manifest().embeddingSpec().orElseThrow();
        validateConfiguredSpace(config, spec);
        warmUp(bundle, spec);
        return new LocalOnnxEmbeddingModel(bundle, spec, config.timeout());
    }

    @Override
    public EmbeddingSpaceDescriptor embeddingSpace(EmbeddingModelConfig config) {
        VerifiedOnnxModelBundle bundle = loadEmbeddingBundle(config);
        OnnxEmbeddingBundleSpec spec = bundle.manifest().embeddingSpec().orElseThrow();
        validateConfiguredSpace(config, spec);
        return EmbeddingSpaceDescriptor.verified(
                providerId(),
                spec.spaceId(),
                bundle.manifest().revision(),
                spec.tokenizerRevision(),
                "onnx-transformer-v1",
                spec.poolingStrategy().name(),
                spec.l2Normalize(),
                spec.vectorDimension(),
                "FLOAT32",
                spec.maxSequenceLength(),
                spec.chunkingStrategy(),
                spec.chunkingRevision(),
                spec.maxWindowTokens(),
                spec.windowOverlapTokens(),
                spec.multiWindowAggregation());
    }

    private VerifiedOnnxModelBundle loadEmbeddingBundle(EmbeddingModelConfig config) {
        if (!providerId().equals(config.providerId())) {
            throw new IllegalArgumentException("ONNX factory received a configuration for another provider");
        }
        try {
            VerifiedOnnxModelBundle bundle = bundleRegistry.loadVerified(config.modelName());
            if (bundle.manifest().embeddingSpec().isEmpty()) {
                throw new IllegalStateException("Verified ONNX bundle does not declare an embedding contract");
            }
            return bundle;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load the configured verified ONNX bundle", e);
        }
    }

    private static void validateConfiguredSpace(EmbeddingModelConfig config, OnnxEmbeddingBundleSpec spec) {
        if (!config.spaceId().isBlank() && !config.spaceId().equals(spec.spaceId())) {
            throw new IllegalStateException("Configured ONNX embedding space does not match the verified bundle");
        }
        if (!config.spaceRevision().isBlank() && !config.spaceRevision().equals(spec.spaceRevision())) {
            throw new IllegalStateException(
                    "Configured ONNX embedding space revision does not match the verified bundle");
        }
    }

    private void warmUp(VerifiedOnnxModelBundle bundle, OnnxEmbeddingBundleSpec spec) {
        if (!warmupEnabled) return;
        List<String> probes =
                spec.warmupProbes().stream().limit(warmupMaxProbes).toList();
        Instant deadline = Instant.now().plus(warmupTimeout);
        CompletableFuture<List<float[]>> result = null;
        try {
            result = scheduler.submit(deadline, () -> runWarmup(bundle, spec, probes, deadline));
            List<float[]> vectors = result.get(warmupTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (vectors.size() != probes.size())
                throw new IllegalStateException("ONNX warm-up returned an unexpected result count");
            metrics.recordWarmup("success");
        } catch (OnnxInferenceSchedulingException | TimeoutException e) {
            if (result != null) result.cancel(true);
            metrics.recordWarmup("failed");
            throw new IllegalStateException("ONNX warm-up did not complete", e);
        } catch (InterruptedException e) {
            if (result != null) result.cancel(true);
            Thread.currentThread().interrupt();
            metrics.recordWarmup("failed");
            throw new IllegalStateException("ONNX warm-up was interrupted", e);
        } catch (ExecutionException e) {
            metrics.recordWarmup("failed");
            throw new IllegalStateException("ONNX warm-up failed", e.getCause());
        }
    }

    private List<float[]> runWarmup(
            VerifiedOnnxModelBundle bundle, OnnxEmbeddingBundleSpec spec, List<String> probes, Instant deadline)
            throws Exception {
        var environment = runtimeManager
                .environment()
                .orElseThrow(() -> new OnnxEmbeddingInferenceException("ONNX Runtime is unavailable"));
        metrics.recordExecutionStage("lease_acquire_started");
        try (OnnxSessionLease lease = sessionLeaseManager.acquire(bundle, deadline)) {
            metrics.recordExecutionStage("lease_acquired");
            return runner.embedAll(environment, lease, lease.tokenizer(), spec, probes, deadline);
        }
    }

    private final class LocalOnnxEmbeddingModel implements EmbeddingModel {
        private final VerifiedOnnxModelBundle bundle;
        private final OnnxEmbeddingBundleSpec spec;
        private final Duration timeout;

        private LocalOnnxEmbeddingModel(
                VerifiedOnnxModelBundle bundle, OnnxEmbeddingBundleSpec spec, Duration timeout) {
            this.bundle = bundle;
            this.spec = spec;
            this.timeout = requirePositive(timeout, "timeout");
        }

        @Override
        public Response<Embedding> embed(String text) {
            return Response.from(new Embedding(embedTexts(List.of(text)).getFirst()));
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return embed(textSegment.text());
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            Objects.requireNonNull(textSegments, "textSegments");
            List<float[]> vectors =
                    embedTexts(textSegments.stream().map(TextSegment::text).toList());
            return Response.from(vectors.stream().map(Embedding::new).toList());
        }

        private List<float[]> embedTexts(List<String> texts) {
            rejectEventLoopThread();
            metrics.recordExecutionStage("thread_guard_passed");
            validateInputLimits(texts);
            metrics.recordExecutionStage("input_validated");
            long started = System.nanoTime();
            Instant deadline = Instant.now().plus(configuredTimeout());
            CompletableFuture<List<float[]>> result = null;
            try {
                result = scheduler.submit(deadline, () -> runVerifiedBundle(texts, deadline));
                List<float[]> vectors = result.get(configuredTimeout().toMillis(), TimeUnit.MILLISECONDS);
                metrics.recordEmbedding("success", System.nanoTime() - started);
                return vectors;
            } catch (OnnxInferenceSchedulingException e) {
                metrics.recordEmbedding("overload_rejected", System.nanoTime() - started);
                throw new IllegalStateException("Local ONNX embedding request was not accepted: " + e.reason(), e);
            } catch (TimeoutException e) {
                if (result != null) result.cancel(true);
                metrics.recordEmbedding("timeout", System.nanoTime() - started);
                throw new IllegalStateException("Local ONNX embedding request timed out", e);
            } catch (InterruptedException e) {
                if (result != null) result.cancel(true);
                Thread.currentThread().interrupt();
                metrics.recordEmbedding("failed", System.nanoTime() - started);
                throw new IllegalStateException("Local ONNX embedding request was interrupted", e);
            } catch (ExecutionException e) {
                if (isAdmissionRejected(e.getCause())) {
                    metrics.recordEmbedding("overload_rejected", System.nanoTime() - started);
                    throw new IllegalStateException("Local ONNX embedding request was not admitted", e.getCause());
                }
                metrics.recordEmbedding("failed", System.nanoTime() - started);
                throw new IllegalStateException("Local ONNX embedding request failed", e.getCause());
            }
        }

        private List<float[]> runVerifiedBundle(List<String> texts, Instant deadline) throws Exception {
            var environment = runtimeManager
                    .environment()
                    .orElseThrow(() -> new OnnxEmbeddingInferenceException("ONNX Runtime is unavailable"));
            metrics.recordExecutionStage("lease_acquire_started");
            try (OnnxSessionLease lease = sessionLeaseManager.acquire(bundle, deadline)) {
                metrics.recordExecutionStage("lease_acquired");
                return runner.embedAll(environment, lease, lease.tokenizer(), spec, texts, deadline);
            }
        }

        private Duration configuredTimeout() {
            return timeout;
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private void validateInputLimits(List<String> texts) {
        if (texts.size() > maxBatchSize) {
            throw new IllegalArgumentException("ONNX embedding batch exceeds configured limit");
        }
        long totalBytes = 0;
        for (String text : texts) {
            int inputBytes =
                    Objects.requireNonNull(text, "texts must not contain null").getBytes(StandardCharsets.UTF_8).length;
            if (inputBytes > maxInputBytes) {
                throw new IllegalArgumentException("ONNX embedding input exceeds configured byte limit");
            }
            totalBytes = Math.addExact(totalBytes, inputBytes);
            if (totalBytes > maxBatchInputBytes) {
                throw new IllegalArgumentException("ONNX embedding batch exceeds configured byte limit");
            }
        }
    }

    static void rejectEventLoopThread() {
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("ONNX embedding must not block a Quarkus event-loop thread");
        }
    }

    private static boolean isAdmissionRejected(Throwable failure) {
        return failure instanceof OnnxSessionAcquireException acquire
                && acquire.reason() == OnnxSessionAcquireException.Reason.ADMISSION_REJECTED;
    }
}
