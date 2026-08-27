package com.amsha.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@ExtendWith(MockitoExtension.class)
class RerankingServiceTest {

    @Mock
    Instance<RerankerPort> rerankerInstance;

    @Mock
    Instance<MeterRegistry> meterRegistryInstance;

    @Mock
    Instance<VectorIndexMetadataPort> vectorMetadataPortInstance;

    @Mock
    VectorIndexMetadataPort vectorMetadataPort;

    @Mock
    MeterRegistry meterRegistry;

    @Mock
    Counter fallbackCounter;

    @Mock
    RerankerPort rerankerPort;

    RerankingService service;

    @BeforeEach
    void setup() {
        service = new RerankingService();
        service.rerankerInstance = rerankerInstance;
        service.meterRegistryInstance = meterRegistryInstance;
        service.vectorMetadataPortInstance = vectorMetadataPortInstance;

        lenient().when(meterRegistryInstance.isUnsatisfied()).thenReturn(false);
        lenient().when(meterRegistryInstance.get()).thenReturn(meterRegistry);
        lenient()
                .when(meterRegistry.counter(eq(RerankingService.FALLBACK_METRIC_NAME), eq("reason"), anyString()))
                .thenReturn(fallbackCounter);

        lenient().when(vectorMetadataPortInstance.isUnsatisfied()).thenReturn(false);
        lenient().when(vectorMetadataPortInstance.get()).thenReturn(vectorMetadataPort);
        lenient().when(rerankerPort.isAvailable()).thenReturn(true);
        lenient()
                .when(vectorMetadataPort.compatibilitySnapshot())
                .thenReturn(Map.of(
                        VectorIndexNamespace.RAG_GLOBAL,
                        new VectorIndexCompatibility(
                                VectorIndexNamespace.RAG_GLOBAL,
                                VectorIndexCompatibility.Status.MATCH,
                                "active-fp",
                                Optional.of("active-fp"))));
    }

    @Test
    void scoreContract_rrfScorePreservedByteForByte_withRerankScoreSeparate() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "First doc text", 0.01639344);
        ScoredChunk chunk2 = new ScoredChunk("c2", "Second doc text", 0.00819672);
        List<ScoredChunk> candidates = List.of(chunk1, chunk2);

        when(rerankerInstance.isUnsatisfied()).thenReturn(false);
        when(rerankerInstance.get()).thenReturn(rerankerPort);
        when(rerankerPort.rerank("query", candidates))
                .thenReturn(List.of(new RerankedChunk(chunk2, 8.45), new RerankedChunk(chunk1, 2.10)));

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(2, results.size());
        assertEquals("c2", results.get(0).chunkId());
        assertEquals(
                0.00819672,
                results.get(0).score(),
                1e-9,
                "Original synthetic RRF score must be byte-for-byte preserved");
        assertEquals(8.45, results.get(0).rerankScore(), 1e-9, "Model-local rerank score must be separate");

        assertEquals("c1", results.get(1).chunkId());
        assertEquals(
                0.01639344,
                results.get(1).score(),
                1e-9,
                "Original synthetic RRF score must be byte-for-byte preserved");
        assertEquals(2.10, results.get(1).rerankScore(), 1e-9, "Model-local rerank score must be separate");
    }

    @Test
    void noProviderFallback_returnsOriginalRrfOrdering_andIncrementsMetric() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "Doc 1", 0.016);
        ScoredChunk chunk2 = new ScoredChunk("c2", "Doc 2", 0.008);
        List<ScoredChunk> candidates = List.of(chunk1, chunk2);

        when(rerankerInstance.isUnsatisfied()).thenReturn(true);

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).chunkId());
        assertEquals(0.016, results.get(0).score());
        assertEquals(0.016, results.get(0).rerankScore());

        assertEquals("c2", results.get(1).chunkId());
        assertEquals(0.008, results.get(1).score());
        assertEquals(0.008, results.get(1).rerankScore());

        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "no_provider");
        verify(fallbackCounter).increment();
    }

    @Test
    void unavailableProviderFallback_returnsOriginalRrfOrdering_andIncrementsNoProviderMetric() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "Doc 1", 0.016);
        ScoredChunk chunk2 = new ScoredChunk("c2", "Doc 2", 0.008);
        List<ScoredChunk> candidates = List.of(chunk1, chunk2);

        when(rerankerInstance.isUnsatisfied()).thenReturn(false);
        when(rerankerInstance.get()).thenReturn(rerankerPort);
        when(rerankerPort.isAvailable()).thenReturn(false);

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).chunkId());
        assertEquals("c2", results.get(1).chunkId());

        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "no_provider");
        verify(fallbackCounter).increment();
        verify(rerankerPort, never()).rerank(anyString(), any());
    }

    @Test
    void executionErrorFallback_returnsOriginalRrfOrdering_logsSafely_andIncrementsMetric() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "Doc 1", 0.016);
        ScoredChunk chunk2 = new ScoredChunk("c2", "Doc 2", 0.008);
        List<ScoredChunk> candidates = List.of(chunk1, chunk2);

        when(rerankerInstance.isUnsatisfied()).thenReturn(false);
        when(rerankerInstance.get()).thenReturn(rerankerPort);
        when(rerankerPort.rerank(anyString(), any()))
                .thenThrow(new RuntimeException("ONNX cross-encoder execution failure"));

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).chunkId());
        assertEquals(0.016, results.get(0).score());

        assertEquals("c2", results.get(1).chunkId());
        assertEquals(0.008, results.get(1).score());

        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "execution_error");
        verify(fallbackCounter).increment();
    }

    @Test
    void descriptorMismatchFallback_normalPath_returnsOriginalRrfOrdering_andIncrementsMetric() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "Doc 1", 0.016);
        ScoredChunk chunk2 = new ScoredChunk("c2", "Doc 2", 0.008);
        List<ScoredChunk> candidates = List.of(chunk1, chunk2);

        when(vectorMetadataPortInstance.isUnsatisfied()).thenReturn(false);
        when(vectorMetadataPortInstance.get()).thenReturn(vectorMetadataPort);
        when(vectorMetadataPort.compatibilitySnapshot())
                .thenReturn(Map.of(
                        VectorIndexNamespace.RAG_GLOBAL,
                        new VectorIndexCompatibility(
                                VectorIndexNamespace.RAG_GLOBAL,
                                VectorIndexCompatibility.Status.MISMATCH,
                                "active-fp",
                                Optional.of("stored-fp"))));

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).chunkId());
        assertEquals(0.016, results.get(0).score());

        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "descriptor_mismatch");
        verify(fallbackCounter).increment();
    }

    @Test
    void candidateValidation_cardinalityMismatch_fallsBackToRrf_andIncrementsMetric() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "Doc 1", 0.016);
        ScoredChunk chunk2 = new ScoredChunk("c2", "Doc 2", 0.008);
        List<ScoredChunk> candidates = List.of(chunk1, chunk2);

        when(rerankerInstance.isUnsatisfied()).thenReturn(false);
        when(rerankerInstance.get()).thenReturn(rerankerPort);
        // Returns only 1 chunk instead of 2
        when(rerankerPort.rerank("query", candidates)).thenReturn(List.of(new RerankedChunk(chunk1, 5.0)));

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).chunkId());
        assertEquals("c2", results.get(1).chunkId());

        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "cardinality_mismatch");
        verify(fallbackCounter).increment();
    }

    @Test
    void candidateValidation_identityMismatch_unknownChunk_fallsBackToRrf_andIncrementsMetric() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "Doc 1", 0.016);
        ScoredChunk chunk2 = new ScoredChunk("c2", "Doc 2", 0.008);
        List<ScoredChunk> candidates = List.of(chunk1, chunk2);

        when(rerankerInstance.isUnsatisfied()).thenReturn(false);
        when(rerankerInstance.get()).thenReturn(rerankerPort);
        // Returns unknown chunkId "c999"
        when(rerankerPort.rerank("query", candidates))
                .thenReturn(List.of(
                        new RerankedChunk(new ScoredChunk("c999", "Hallucinated", 0.5), 9.0),
                        new RerankedChunk(chunk1, 5.0)));

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).chunkId());
        assertEquals("c2", results.get(1).chunkId());

        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "identity_mismatch");
        verify(fallbackCounter).increment();
    }

    @Test
    void candidateValidation_duplicateChunk_fallsBackToRrf_andIncrementsMetric() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "Doc 1", 0.016);
        ScoredChunk chunk2 = new ScoredChunk("c2", "Doc 2", 0.008);
        List<ScoredChunk> candidates = List.of(chunk1, chunk2);

        when(rerankerInstance.isUnsatisfied()).thenReturn(false);
        when(rerankerInstance.get()).thenReturn(rerankerPort);
        // Returns duplicate "c1"
        when(rerankerPort.rerank("query", candidates))
                .thenReturn(List.of(new RerankedChunk(chunk1, 9.0), new RerankedChunk(chunk1, 5.0)));

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).chunkId());
        assertEquals("c2", results.get(1).chunkId());

        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "identity_mismatch");
        verify(fallbackCounter).increment();
    }

    @Test
    void candidateValidation_scoreTampering_preservesOriginalRrfScore() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "Doc 1", 0.016);
        ScoredChunk chunk2 = new ScoredChunk("c2", "Doc 2", 0.008);
        List<ScoredChunk> candidates = List.of(chunk1, chunk2);

        when(rerankerInstance.isUnsatisfied()).thenReturn(false);
        when(rerankerInstance.get()).thenReturn(rerankerPort);
        // Reranker returns RerankedChunk with modified internal chunk score (tampered to 999.0)
        when(rerankerPort.rerank("query", candidates))
                .thenReturn(List.of(
                        new RerankedChunk(new ScoredChunk("c2", "Doc 2", 999.0), 10.0),
                        new RerankedChunk(new ScoredChunk("c1", "Doc 1", 888.0), 5.0)));

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(2, results.size());
        assertEquals("c2", results.get(0).chunkId());
        assertEquals(
                0.008, results.get(0).score(), 1e-9, "RerankingService MUST guarantee original RRF score preservation");
        assertEquals(10.0, results.get(0).rerankScore());

        assertEquals("c1", results.get(1).chunkId());
        assertEquals(
                0.016, results.get(1).score(), 1e-9, "RerankingService MUST guarantee original RRF score preservation");
        assertEquals(5.0, results.get(1).rerankScore());
    }

    @Test
    void descriptorMismatchFallback_incrementsMetric() {
        service.recordDescriptorMismatchFallback();

        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "descriptor_mismatch");
        verify(fallbackCounter).increment();
    }

    @Test
    void emptyOrNullCandidates_returnsEmptyListWithoutFallbackMetrics() {
        List<RerankedChunk> emptyResults = service.rerank("query", List.of());
        assertTrue(emptyResults.isEmpty());

        List<RerankedChunk> nullResults = service.rerank("query", null);
        assertTrue(nullResults.isEmpty());
    }

    @Test
    void emptyRerankResult_fallsBackToOriginalOrdering_andIncrementsMetric() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "Doc 1", 0.016);
        List<ScoredChunk> candidates = List.of(chunk1);

        when(rerankerInstance.isUnsatisfied()).thenReturn(false);
        when(rerankerInstance.get()).thenReturn(rerankerPort);
        when(rerankerPort.rerank(anyString(), any())).thenReturn(List.of());

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).chunkId());
        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "empty_result");
        verify(fallbackCounter).increment();
    }

    @Test
    void candidateValidation_unsortedScores_fallsBackToRrf_andIncrementsMetric() {
        ScoredChunk chunk1 = new ScoredChunk("c1", "Doc 1", 0.016);
        ScoredChunk chunk2 = new ScoredChunk("c2", "Doc 2", 0.008);
        List<ScoredChunk> candidates = List.of(chunk1, chunk2);
        when(rerankerInstance.isUnsatisfied()).thenReturn(false);
        when(rerankerInstance.get()).thenReturn(rerankerPort);
        when(rerankerPort.rerank("query", candidates))
                .thenReturn(List.of(new RerankedChunk(chunk1, 1.0), new RerankedChunk(chunk2, 2.0)));

        List<RerankedChunk> results = service.rerank("query", candidates);

        assertEquals(
                List.of("c1", "c2"),
                results.stream().map(RerankedChunk::chunkId).toList());
        assertEquals(0.016, results.get(0).score());
        assertEquals(0.008, results.get(1).score());
        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "order_invalid");
        verify(fallbackCounter).increment();
    }

    @Test
    void metadataUnavailable_fallsBackBeforeInvokingReranker() {
        when(vectorMetadataPortInstance.isUnsatisfied()).thenReturn(true);

        List<RerankedChunk> results = service.rerank("query", List.of(new ScoredChunk("c1", "Doc 1", 0.016)));

        assertEquals("c1", results.get(0).chunkId());
        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "metadata_unavailable");
        verify(fallbackCounter).increment();
    }

    @Test
    void unavailableMetadataStatus_fallsBackBeforeInvokingReranker() {
        when(vectorMetadataPort.compatibilitySnapshot())
                .thenReturn(Map.of(
                        VectorIndexNamespace.RAG_GLOBAL,
                        new VectorIndexCompatibility(
                                VectorIndexNamespace.RAG_GLOBAL,
                                VectorIndexCompatibility.Status.UNAVAILABLE,
                                "active-fp",
                                Optional.empty())));

        List<RerankedChunk> results = service.rerank("query", List.of(new ScoredChunk("c1", "Doc 1", 0.016)));

        assertEquals("c1", results.get(0).chunkId());
        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "metadata_unavailable");
        verify(fallbackCounter).increment();
    }

    @Test
    void metadataLookupFailure_fallsBackSafely() {
        when(vectorMetadataPort.compatibilitySnapshot()).thenThrow(new IllegalStateException("metadata unavailable"));

        List<RerankedChunk> results = service.rerank("query", List.of(new ScoredChunk("c1", "Doc 1", 0.016)));

        assertEquals("c1", results.get(0).chunkId());
        verify(meterRegistry).counter(RerankingService.FALLBACK_METRIC_NAME, "reason", "metadata_unavailable");
        verify(fallbackCounter).increment();
    }
}
