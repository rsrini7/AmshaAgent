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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.rag.EmbeddingModelConfig;
import com.amsha.rag.EmbeddingProviderFactory;
import com.amsha.rag.EmbeddingSpaceDescriptor;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.Context;
import io.vertx.core.Vertx;

/** Opt-in RAG embedding provider backed exclusively by verified local ONNX bundles. */
@ApplicationScoped
public class OnnxEmbeddingProviderFactory implements EmbeddingProviderFactory {

    private final OnnxModelBundleRegistry bundleRegistry;
    private final OnnxRuntimeManager runtimeManager;
    private final OnnxSessionLeaseManager sessionLeaseManager;
    private final OnnxInferenceScheduler scheduler;
    private final OnnxEmbeddingRunner runner;
    private final long maxInputBytes;
    private final long maxBatchInputBytes;
    private final int maxBatchSize;

    @Inject
    public OnnxEmbeddingProviderFactory(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            @ConfigProperty(name = "amsha.onnx.max-input-bytes", defaultValue = "262144") long maxInputBytes,
            @ConfigProperty(name = "amsha.onnx.max-batch-input-bytes", defaultValue = "1048576")
                    long maxBatchInputBytes,
            @ConfigProperty(name = "amsha.onnx.max-batch-size", defaultValue = "32") int maxBatchSize) {
        this(
                bundleRegistry,
                runtimeManager,
                sessionLeaseManager,
                scheduler,
                new OnnxEmbeddingRunner(),
                maxInputBytes,
                maxBatchInputBytes,
                maxBatchSize);
    }

    OnnxEmbeddingProviderFactory(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            OnnxEmbeddingRunner runner,
            long maxInputBytes,
            long maxBatchInputBytes,
            int maxBatchSize) {
        this.bundleRegistry = Objects.requireNonNull(bundleRegistry, "bundleRegistry");
        this.runtimeManager = Objects.requireNonNull(runtimeManager, "runtimeManager");
        this.sessionLeaseManager = Objects.requireNonNull(sessionLeaseManager, "sessionLeaseManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.maxInputBytes = requirePositive(maxInputBytes, "maxInputBytes");
        this.maxBatchInputBytes = requirePositive(maxBatchInputBytes, "maxBatchInputBytes");
        this.maxBatchSize = requirePositive(maxBatchSize, "maxBatchSize");
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
        return new LocalOnnxEmbeddingModel(bundle, spec, config.timeout());
    }

    @Override
    public EmbeddingSpaceDescriptor embeddingSpace(EmbeddingModelConfig config) {
        OnnxEmbeddingBundleSpec spec =
                loadEmbeddingBundle(config).manifest().embeddingSpec().orElseThrow();
        validateConfiguredSpace(config, spec);
        return EmbeddingSpaceDescriptor.configured(providerId(), spec.spaceId(), spec.spaceRevision());
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
            validateInputLimits(texts);
            Instant deadline = Instant.now().plus(configuredTimeout());
            try {
                var result = scheduler.submit(deadline, () -> runVerifiedBundle(texts, deadline));
                return result.get(configuredTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (OnnxInferenceSchedulingException e) {
                throw new IllegalStateException("Local ONNX embedding request was not accepted: " + e.reason(), e);
            } catch (TimeoutException e) {
                throw new IllegalStateException("Local ONNX embedding request timed out", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Local ONNX embedding request was interrupted", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Local ONNX embedding request failed", e.getCause());
            }
        }

        private List<float[]> runVerifiedBundle(List<String> texts, Instant deadline) throws Exception {
            var environment = runtimeManager
                    .environment()
                    .orElseThrow(() -> new OnnxEmbeddingInferenceException("ONNX Runtime is unavailable"));
            try (OnnxSessionLease lease = sessionLeaseManager.acquire(bundle, deadline)) {
                return runner.embedAll(environment, lease.session(), lease.tokenizer(), spec, texts);
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

    private static void rejectEventLoopThread() {
        Context context = Vertx.currentContext();
        if (context != null && context.isEventLoopContext()) {
            throw new IllegalStateException("ONNX embedding must not block a Quarkus event-loop thread");
        }
    }
}
