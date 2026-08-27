package com.amsha.localml.onnx;

import ai.onnxruntime.OrtEnvironment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.rag.RerankedChunk;
import com.amsha.rag.RerankerPort;
import com.amsha.rag.ScoredChunk;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;
import io.vertx.core.Context;

/**
 * Local ONNX cross-encoder implementation of {@link RerankerPort}.
 * Preserves {@link ScoredChunk#score()} and uses {@code rerankScore} exclusively for reordering.
 */
@Unremovable
@ApplicationScoped
public class LocalOnnxScoringAdapter implements RerankerPort {

    private final OnnxModelBundleRegistry bundleRegistry;
    private final OnnxRuntimeManager runtimeManager;
    private final OnnxSessionLeaseManager sessionLeaseManager;
    private final OnnxInferenceScheduler scheduler;
    private final OnnxScoringRunner runner;
    private final OnnxInferenceMetrics metrics;
    private final boolean enabled;
    private final boolean onnxEnabled;
    private final String bundleId;
    private final Duration timeout;
    private final int maxCandidates;
    private final long maxInputBytes;

    @Inject
    public LocalOnnxScoringAdapter(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            OnnxInferenceMetrics metrics,
            @ConfigProperty(name = "amsha.rag.reranker.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "amsha.onnx.enabled", defaultValue = "false") boolean onnxEnabled,
            @ConfigProperty(name = "amsha.rag.reranker.bundle-id", defaultValue = "bge-reranker-v1") String bundleId,
            @ConfigProperty(name = "amsha.rag.reranker.timeout", defaultValue = "PT5S") Duration timeout,
            @ConfigProperty(name = "amsha.rag.reranker.max-candidates", defaultValue = "50") int maxCandidates,
            @ConfigProperty(name = "amsha.rag.reranker.max-input-bytes", defaultValue = "262144") long maxInputBytes) {
        this(
                bundleRegistry,
                runtimeManager,
                sessionLeaseManager,
                scheduler,
                new OnnxScoringRunner(metrics),
                metrics,
                enabled,
                onnxEnabled,
                bundleId,
                timeout,
                maxCandidates,
                maxInputBytes);
    }

    LocalOnnxScoringAdapter(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            OnnxScoringRunner runner,
            OnnxInferenceMetrics metrics,
            boolean enabled,
            boolean onnxEnabled,
            String bundleId,
            Duration timeout,
            int maxCandidates,
            long maxInputBytes) {
        this.bundleRegistry = Objects.requireNonNull(bundleRegistry, "bundleRegistry");
        this.runtimeManager = Objects.requireNonNull(runtimeManager, "runtimeManager");
        this.sessionLeaseManager = Objects.requireNonNull(sessionLeaseManager, "sessionLeaseManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.metrics = metrics;
        this.enabled = enabled;
        this.onnxEnabled = onnxEnabled;
        this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.maxCandidates = maxCandidates > 0 ? maxCandidates : 50;
        this.maxInputBytes = maxInputBytes > 0 ? maxInputBytes : 262144L;
    }

    @Override
    public boolean isAvailable() {
        return enabled && onnxEnabled && runtimeManager.isReady();
    }

    @Override
    public List<RerankedChunk> rerank(String query, List<ScoredChunk> candidates) {
        if (!isAvailable()) {
            throw new IllegalStateException("Local ONNX reranker is disabled or ONNX runtime is unavailable");
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query must not be null or blank");
        }

        rejectEventLoopThread();
        if (metrics != null) metrics.recordScoringExecutionStage("thread_guard_passed");

        List<ScoredChunk> targetCandidates =
                candidates.size() > maxCandidates ? candidates.subList(0, maxCandidates) : candidates;
        List<ScoredChunk> trailingCandidates =
                candidates.size() > maxCandidates ? candidates.subList(maxCandidates, candidates.size()) : List.of();

        validateInputLimits(query, targetCandidates);
        if (metrics != null) metrics.recordScoringExecutionStage("input_validated");

        long started = System.nanoTime();
        Instant deadline = Instant.now().plus(timeout);
        CompletableFuture<List<RerankedChunk>> result = null;

        try {
            result =
                    scheduler.submit(deadline, () -> runScoring(query, targetCandidates, trailingCandidates, deadline));
            List<RerankedChunk> reranked = result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (metrics != null) metrics.recordScoring("success", System.nanoTime() - started);
            return reranked;
        } catch (OnnxInferenceSchedulingException e) {
            if (metrics != null) metrics.recordScoring("overload_rejected", System.nanoTime() - started);
            throw new IllegalStateException("Local ONNX reranking request was not accepted: " + e.reason(), e);
        } catch (TimeoutException e) {
            if (result != null) result.cancel(true);
            if (metrics != null) metrics.recordScoring("timeout", System.nanoTime() - started);
            throw new IllegalStateException("Local ONNX reranking request timed out", e);
        } catch (InterruptedException e) {
            if (result != null) result.cancel(true);
            Thread.currentThread().interrupt();
            if (metrics != null) metrics.recordScoring("failed", System.nanoTime() - started);
            throw new IllegalStateException("Local ONNX reranking request was interrupted", e);
        } catch (ExecutionException e) {
            if (metrics != null) metrics.recordScoring("failed", System.nanoTime() - started);
            throw new IllegalStateException("Local ONNX reranking request failed", e.getCause());
        }
    }

    private volatile boolean warmedUp;

    private void ensureWarmedUp(
            OrtEnvironment environment, OnnxSessionLease lease, OnnxScoringBundleSpec spec, Instant deadline) {
        if (warmedUp) {
            return;
        }
        synchronized (this) {
            if (warmedUp) {
                return;
            }
            for (String probe : spec.warmupProbes()) {
                try {
                    runner.scorePairs(environment, lease, lease.tokenizer(), spec, probe, List.of(probe), deadline);
                } catch (Exception _) {
                    // Non-fatal probe failure during warmup
                }
            }
            warmedUp = true;
        }
    }

    private List<RerankedChunk> runScoring(
            String query, List<ScoredChunk> candidates, List<ScoredChunk> trailingCandidates, Instant deadline)
            throws Exception {
        VerifiedOnnxModelBundle bundle = bundleRegistry.loadVerified(bundleId);
        OnnxScoringBundleSpec spec = bundle.manifest()
                .scoringSpec()
                .orElseThrow(() -> new IllegalStateException(
                        "Model bundle " + bundleId + " does not declare a cross-encoder scoring contract"));

        OrtEnvironment environment = runtimeManager
                .environment()
                .orElseThrow(() -> new IllegalStateException("ONNX Runtime is unavailable"));

        if (metrics != null) metrics.recordScoringExecutionStage("lease_acquire_started");
        try (OnnxSessionLease lease = sessionLeaseManager.acquire(bundle, deadline)) {
            if (metrics != null) metrics.recordScoringExecutionStage("lease_acquired");
            ensureWarmedUp(environment, lease, spec, deadline);
            List<String> candidateTexts =
                    candidates.stream().map(ScoredChunk::text).toList();

            double[] scores =
                    runner.scorePairs(environment, lease, lease.tokenizer(), spec, query, candidateTexts, deadline);

            if (scores.length != candidates.size()) {
                throw new IllegalStateException(
                        "Scoring returned " + scores.length + " scores for " + candidates.size() + " candidates");
            }

            List<RerankedChunk> reranked = new ArrayList<>(candidates.size() + trailingCandidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                reranked.add(new RerankedChunk(candidates.get(i), scores[i]));
            }

            reranked.sort(Comparator.comparingDouble(RerankedChunk::rerankScore).reversed());

            double floorScore =
                    reranked.isEmpty() ? 0.0 : reranked.get(reranked.size() - 1).rerankScore();
            for (int j = 0; j < trailingCandidates.size(); j++) {
                double trailingScore = floorScore - 1.0 - j;
                reranked.add(new RerankedChunk(trailingCandidates.get(j), trailingScore));
            }

            return List.copyOf(reranked);
        }
    }

    private void validateInputLimits(String query, List<ScoredChunk> candidates) {
        long queryBytes = query.getBytes(StandardCharsets.UTF_8).length;
        if (queryBytes > maxInputBytes) {
            throw new IllegalArgumentException("Query text exceeds configured input byte limit");
        }
        for (ScoredChunk candidate : candidates) {
            if (candidate == null
                    || candidate.text() == null
                    || candidate.text().isBlank()) {
                throw new IllegalArgumentException("Candidate chunk and text must not be null or blank");
            }
            String text = candidate.text();
            if (text.getBytes(StandardCharsets.UTF_8).length > maxInputBytes) {
                throw new IllegalArgumentException("Candidate chunk content exceeds configured input byte limit");
            }
        }
    }

    static void rejectEventLoopThread() {
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("ONNX reranking must not block a Quarkus event-loop thread");
        }
    }
}
