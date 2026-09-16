package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.api.IngestResult;
import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.MemoryEntryDto;
import com.amsha.persistence.api.PersistenceScopeProbe;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.amsha.persistence.common.SessionKnowledgeBase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SqliteMemoryRetrievalAdapterTest {

    @Mock
    GlobalKnowledgeBase globalKnowledgeBase;

    @Mock
    Instance<SessionKnowledgeBase> sessionKnowledgeBase;

    @Mock
    CompactionStorePort compactionStore;

    @Mock
    FtsConnectionInterceptor ftsInterceptor;

    @Mock
    VectorIndexMetadataPort vectorIndexMetadata;

    @Mock
    PersistenceScopeProbe scopeProbe;

    private SqliteMemoryRetrievalAdapter adapter;

    @BeforeEach
    @SuppressWarnings("UnnecessaryAssignment")
    void setUp() {
        adapter = new SqliteMemoryRetrievalAdapter();
        adapter.globalKb = globalKnowledgeBase;
        adapter.sessionKb = sessionKnowledgeBase;
        adapter.compactionRepo = compactionStore;
        adapter.ftsInterceptor = ftsInterceptor;
        adapter.vectorIndexMetadata = vectorIndexMetadata;
        adapter.scopeProbe = scopeProbe;
        adapter.maxSourceChars = 400;
    }

    @Test
    void descriptorMismatchFallsBackToFullTextWithoutEmbedding() {
        CompactionSummaryDto summary =
                new CompactionSummaryDto("session-1", "matching text", Instant.now(), 1, "user-1");
        when(compactionStore.search("user-1", "matching", 4)).thenReturn(List.of(summary));
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(false);

        var results = adapter.search("matching", 2, "user-1");

        assertEquals(1, results.size());
        assertEquals("session-1", results.getFirst().chunkId());
        verify(globalKnowledgeBase, never()).search("matching", 4);
    }

    // ---------- hybrid path: global + session vector results, RRF, truncation ----------

    @Test
    void hybridSearchMergesFullTextAndGlobalVectorResults() {
        when(compactionStore.search("user-1", "q", 4))
                .thenReturn(List.of(new CompactionSummaryDto("session-1", "fts text", Instant.now(), 1, "user-1")));
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(true);
        when(globalKnowledgeBase.search("q", 4)).thenReturn(List.of(new MemoryChunkDto("global-1", "vec text", 0.9)));

        var results = adapter.search("q", 2, "user-1");

        assertEquals(2, results.size());
        // Both lists rank their entry first, so each gets exactly one 1/(60+1) contribution.
        assertEquals(1.0 / 61, results.get(0).score(), 1e-12);
        assertEquals(1.0 / 61, results.get(1).score(), 1e-12);
        assertEquals(
                List.of("session-1", "global-1"),
                results.stream().map(MemoryChunkDto::chunkId).toList());
    }

    @Test
    void sessionScopedVectorResultsJoinTheMergeWhenWorkspaceScopeIsActive() {
        SessionKnowledgeBase sessionKb = mock(SessionKnowledgeBase.class);
        when(compactionStore.search("user-1", "q", 4)).thenReturn(List.of());
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(false);
        when(sessionKnowledgeBase.get()).thenReturn(sessionKb);
        when(scopeProbe.workspaceScopeActive()).thenReturn(true);
        when(globalKnowledgeBase.search("q", 4)).thenReturn(List.of());
        when(sessionKb.search("q", 4)).thenReturn(List.of(new MemoryChunkDto("session-chunk", "session text", 0.5)));

        var results = adapter.search("q", 2, "user-1");

        assertEquals(1, results.size());
        assertEquals("session-chunk", results.get(0).chunkId());
        assertEquals("session text", results.get(0).text());
    }

    @Test
    void sessionScopedSearchIsSkippedWhenNoWorkspaceScopeIsActive() {
        when(compactionStore.search("user-1", "q", 4)).thenReturn(List.of());
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(false);
        when(scopeProbe.workspaceScopeActive()).thenReturn(false);
        when(globalKnowledgeBase.search("q", 4)).thenReturn(List.of(new MemoryChunkDto("global-1", "text", 0.5)));

        var results = adapter.search("q", 2, "user-1");

        assertEquals(
                List.of("global-1"),
                results.stream().map(MemoryChunkDto::chunkId).toList());
        verify(sessionKnowledgeBase, never()).get();
    }

    @Test
    void vectorResultsAreRankedByDescendingScoreBeforeMerging() {
        when(compactionStore.search("user-1", "q", 4)).thenReturn(List.of());
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(true);
        // Deliberately unsorted: the adapter must sort by score before assigning RRF ranks.
        when(globalKnowledgeBase.search("q", 4))
                .thenReturn(List.of(new MemoryChunkDto("low", "low", 0.1), new MemoryChunkDto("high", "high", 0.9)));

        var results = adapter.search("q", 2, "user-1");

        assertEquals(
                List.of("high", "low"),
                results.stream().map(MemoryChunkDto::chunkId).toList());
    }

    @Test
    void longTextIsTruncatedToMaxSourceChars() {
        when(compactionStore.search("user-1", "q", 4))
                .thenReturn(List.of(new CompactionSummaryDto("session-1", "abcdefghij", Instant.now(), 1, "user-1")));
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(true);
        when(globalKnowledgeBase.search("q", 4)).thenReturn(List.of());
        adapter.maxSourceChars = 5;

        var results = adapter.search("q", 2, "user-1");

        assertEquals("abcde…", results.get(0).text());
    }

    @Test
    void textAtExactlyMaxSourceCharsIsLeftAlone() {
        when(compactionStore.search("user-1", "q", 4))
                .thenReturn(List.of(new CompactionSummaryDto("session-1", "abcde", Instant.now(), 1, "user-1")));
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(true);
        when(globalKnowledgeBase.search("q", 4)).thenReturn(List.of());
        adapter.maxSourceChars = 5;

        var results = adapter.search("q", 2, "user-1");

        assertEquals("abcde", results.get(0).text());
    }

    // ---------- index() ----------

    @Test
    void indexUsesTheGlobalChunkIdWhenIngestSucceeds() {
        when(globalKnowledgeBase.ingest("text")).thenReturn("chunk-1");
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(true);

        IngestResult result = adapter.index(new MemoryEntryDto("entry-1", "text", Map.of()));

        assertEquals("chunk-1", result.id());
        assertEquals(false, result.isGlobalFallback());
    }

    @Test
    void indexFallsBackToASessionIdWhenTheGlobalIngestFails() {
        when(globalKnowledgeBase.ingest("text")).thenReturn(null);
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(true);

        IngestResult result = adapter.index(new MemoryEntryDto("entry-1", "text", Map.of()));

        assertEquals("session-entry-1", result.id());
        assertEquals(true, result.isGlobalFallback());
    }

    @Test
    void indexGeneratesAFallbackIdWhenTheEntryHasNoId() {
        when(globalKnowledgeBase.ingest("text")).thenReturn(null);
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(true);

        IngestResult result = adapter.index(new MemoryEntryDto(null, "text", Map.of()));

        assertNotNull(result.id());
        assertNotEquals("session-null", result.id());
        assertEquals(true, result.isGlobalFallback());
    }

    @Test
    void indexForwardsToTheSessionKnowledgeBaseWhenAvailable() {
        SessionKnowledgeBase sessionKb = mock(SessionKnowledgeBase.class);
        when(globalKnowledgeBase.ingest("text")).thenReturn("chunk-1");
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(false);
        when(sessionKnowledgeBase.get()).thenReturn(sessionKb);

        adapter.index(new MemoryEntryDto("entry-1", "text", Map.of()));

        verify(sessionKb).ingest("chunk-1", "text");
    }

    @Test
    void indexForwardsTheFallbackIdWhenTheGlobalIngestFailed() {
        SessionKnowledgeBase sessionKb = mock(SessionKnowledgeBase.class);
        when(globalKnowledgeBase.ingest("text")).thenReturn(null);
        when(sessionKnowledgeBase.isUnsatisfied()).thenReturn(false);
        when(sessionKnowledgeBase.get()).thenReturn(sessionKb);

        adapter.index(new MemoryEntryDto("entry-1", "text", Map.of()));

        verify(sessionKb).ingest("session-entry-1", "text");
    }

    // ---------- capabilities() ----------

    @Test
    void capabilitiesReflectTheVectorFlags() {
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);

        assertTrue(adapter.capabilities().supportsVectorSearch());
        assertTrue(adapter.capabilities().supportsFullTextSearch());
        assertFalse(adapter.capabilities().supportsAdvisoryLocks());

        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(false);
        assertFalse(adapter.capabilities().supportsVectorSearch());
    }
}
