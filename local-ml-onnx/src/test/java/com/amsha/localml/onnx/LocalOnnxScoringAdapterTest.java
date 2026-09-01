package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtEnvironment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.rag.RerankedChunk;
import com.amsha.rag.ScoredChunk;

import org.junit.jupiter.api.Test;

import io.vertx.core.Vertx;

class LocalOnnxScoringAdapterTest {

    private static ScoredChunk scoredChunk(String id, String text, double score) {
        return new ScoredChunk(id, text, score);
    }

    @Test
    void isAvailableReflectsFlagsAndRuntime() {
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);

        LocalOnnxScoringAdapter adapterAvailable = new LocalOnnxScoringAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(OnnxScoringRunner.class),
                null,
                true,
                true,
                "bge-reranker-v1",
                Duration.ofSeconds(5),
                50,
                262144L);
        assertTrue(adapterAvailable.isAvailable());

        LocalOnnxScoringAdapter adapterDisabled = new LocalOnnxScoringAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(OnnxScoringRunner.class),
                null,
                false,
                true,
                "bge-reranker-v1",
                Duration.ofSeconds(5),
                50,
                262144L);
        assertFalse(adapterDisabled.isAvailable());

        LocalOnnxScoringAdapter adapterOnnxDisabled = new LocalOnnxScoringAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(OnnxScoringRunner.class),
                null,
                true,
                false,
                "bge-reranker-v1",
                Duration.ofSeconds(5),
                50,
                262144L);
        assertFalse(adapterOnnxDisabled.isAvailable());
    }

    @Test
    void disabledAdapterThrowsIllegalStateException() {
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);

        LocalOnnxScoringAdapter adapter = new LocalOnnxScoringAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(OnnxScoringRunner.class),
                null,
                false,
                true,
                "bge-reranker-v1",
                Duration.ofSeconds(5),
                50,
                262144L);

        assertThrows(
                IllegalStateException.class,
                () -> adapter.rerank("test query", List.of(scoredChunk("1", "text", 0.5))));
    }

    @Test
    void emptyCandidatesReturnsEmptyList() {
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);

        LocalOnnxScoringAdapter adapter = new LocalOnnxScoringAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(OnnxScoringRunner.class),
                null,
                true,
                true,
                "bge-reranker-v1",
                Duration.ofSeconds(5),
                50,
                262144L);

        assertEquals(0, adapter.rerank("test query", List.of()).size());
        assertEquals(0, adapter.rerank("test query", null).size());
    }

    @Test
    void blankQueryThrowsIllegalArgumentException() {
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);

        LocalOnnxScoringAdapter adapter = new LocalOnnxScoringAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(OnnxScoringRunner.class),
                null,
                true,
                true,
                "bge-reranker-v1",
                Duration.ofSeconds(5),
                50,
                262144L);

        assertThrows(IllegalArgumentException.class, () -> adapter.rerank("", List.of(scoredChunk("1", "text", 0.5))));
        assertThrows(
                IllegalArgumentException.class, () -> adapter.rerank("   ", List.of(scoredChunk("1", "text", 0.5))));
    }

    @Test
    void blankOrNullCandidateTextThrowsIllegalArgumentException() {
        OnnxRuntimeManager runtime = mock(OnnxRuntimeManager.class);
        when(runtime.isReady()).thenReturn(true);

        LocalOnnxScoringAdapter adapter = new LocalOnnxScoringAdapter(
                mock(OnnxModelBundleRegistry.class),
                runtime,
                mock(OnnxSessionLeaseManager.class),
                mock(OnnxInferenceScheduler.class),
                mock(OnnxScoringRunner.class),
                null,
                true,
                true,
                "bge-reranker-v1",
                Duration.ofSeconds(5),
                50,
                262144L);

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.rerank("test query", List.of(scoredChunk("1", null, 0.5))));
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.rerank("test query", List.of(scoredChunk("1", "  ", 0.5))));
    }

    @Test
    void rejectsActualEventLoopThread() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            CompletableFuture<Throwable> result = new CompletableFuture<>();
            vertx.runOnContext(_ -> {
                try {
                    LocalOnnxScoringAdapter.rejectEventLoopThread();
                    result.complete(null);
                } catch (Throwable failure) {
                    result.complete(failure);
                }
            });

            assertInstanceOf(IllegalStateException.class, result.get(5, TimeUnit.SECONDS));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void allowsVirtualThread() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                LocalOnnxScoringAdapter.rejectEventLoopThread();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });

        thread.join();
        assertNull(failure.get());
    }

    @Test
    void rerankSortsDescendingAndPreservesOriginalScoreAndCardinality() throws Exception {
        OnnxModelBundleRegistry registry = mock(OnnxModelBundleRegistry.class);
        OnnxRuntimeManager runtimeManager = mock(OnnxRuntimeManager.class);
        OnnxSessionLeaseManager leaseManager = mock(OnnxSessionLeaseManager.class);
        OnnxInferenceScheduler scheduler = mock(OnnxInferenceScheduler.class);
        OnnxScoringRunner runner = mock(OnnxScoringRunner.class);

        when(runtimeManager.isReady()).thenReturn(true);

        OnnxScoringBundleSpec spec = new OnnxScoringBundleSpec(
                "text-pair",
                "v1",
                "input_ids",
                "attention_mask",
                null,
                "logits",
                384,
                0L,
                "relevance",
                OnnxScoringBundleSpec.ScoreDirection.HIGHER_IS_BETTER,
                OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE,
                List.of("probe"));

        OnnxModelBundleManifest manifest = new OnnxModelBundleManifest(
                "bge-reranker-v1",
                "v1",
                "model.onnx",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                100L,
                "tokenizer.json",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                100L,
                400L,
                null,
                null,
                spec,
                new OnnxModelBundleManifest.OnnxModelBundleSource(
                        "https://example.com/model", "v1", "model.onnx", "tokenizer.json", "Apache-2.0"));

        VerifiedOnnxModelBundle verifiedBundle =
                new VerifiedOnnxModelBundle(manifest, Path.of("model.onnx"), Path.of("tokenizer.json"));

        when(registry.loadVerified("bge-reranker-v1")).thenReturn(verifiedBundle);
        when(runtimeManager.environment()).thenReturn(Optional.of(mock(OrtEnvironment.class)));
        OnnxSessionLease lease = mock(OnnxSessionLease.class);
        when(leaseManager.acquire(eq(verifiedBundle), any())).thenReturn(lease);

        when(scheduler.submit(any(), any())).thenAnswer(invocation -> {
            Callable<?> task = invocation.getArgument(1);
            return CompletableFuture.completedFuture(task.call());
        });

        // 4 candidates with maxCandidates = 3 (chunkD will be trailing)
        ScoredChunk chunkA = scoredChunk("A", "text A", 0.3);
        ScoredChunk chunkB = scoredChunk("B", "text B", 0.8);
        ScoredChunk chunkC = scoredChunk("C", "text C", 0.5);
        ScoredChunk chunkD = scoredChunk("D", "text D", 0.2);

        // Cross-encoder scores for first 3: chunkA = 0.95 (best), chunkB = 0.10 (worst), chunkC = 0.60 (middle)
        when(runner.scorePairs(
                        any(),
                        any(),
                        any(),
                        eq(spec),
                        eq("test query"),
                        eq(List.of("text A", "text B", "text C")),
                        any()))
                .thenReturn(new double[] {0.95, 0.10, 0.60});

        LocalOnnxScoringAdapter adapter = new LocalOnnxScoringAdapter(
                registry,
                runtimeManager,
                leaseManager,
                scheduler,
                runner,
                null,
                true,
                true,
                "bge-reranker-v1",
                Duration.ofSeconds(5),
                3, // maxCandidates = 3
                262144L);

        List<RerankedChunk> results = adapter.rerank("test query", List.of(chunkA, chunkB, chunkC, chunkD));

        // Exact cardinality preservation: 4 candidates in -> 4 reranked chunks out!
        assertEquals(4, results.size());

        // Top 3 sorted descending by rerankScore: chunkA (0.95), chunkC (0.60), chunkB (0.10)
        assertEquals("A", results.get(0).chunk().chunkId());
        assertEquals(0.95, results.get(0).rerankScore(), 0.001);
        assertEquals(0.3, results.get(0).chunk().score(), 0.001); // original score preserved!

        assertEquals("C", results.get(1).chunk().chunkId());
        assertEquals(0.60, results.get(1).rerankScore(), 0.001);
        assertEquals(0.5, results.get(1).chunk().score(), 0.001); // original score preserved!

        assertEquals("B", results.get(2).chunk().chunkId());
        assertEquals(0.10, results.get(2).rerankScore(), 0.001);
        assertEquals(0.8, results.get(2).chunk().score(), 0.001); // original score preserved!

        // Trailing chunkD preserved at index 3 with finite descending score below lowest scored candidate
        assertEquals("D", results.get(3).chunk().chunkId());
        assertEquals(0.2, results.get(3).chunk().score(), 0.001);
        assertTrue(Double.isFinite(results.get(3).rerankScore()), "Trailing score must be finite");
        assertTrue(
                results.get(3).rerankScore() < results.get(2).rerankScore(),
                "Trailing score must be strictly below lowest scored candidate");
    }
}
