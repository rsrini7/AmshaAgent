package com.arcclaw.rag;

import com.arcclaw.core.persistence.FtsConnectionInterceptor;
import com.arcclaw.core.state.CompactionSummaryRepository;
import com.arcclaw.core.state.CompactionSummaryEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwoTierMemoryServiceTest {

    @Mock
    FtsConnectionInterceptor ftsInterceptor;
    @Mock
    CompactionSummaryRepository compactionRepo;
    @Mock
    GlobalKnowledgeBase globalKb;
    @Mock
    SessionKnowledgeBase sessionKb;

    @InjectMocks
    @Spy
    TwoTierMemoryService service;

    @Test
    void testFastPathWhenVectorSearchDisabled() {
        // Arrange
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(false);

        CompactionSummaryEntity e1 = new CompactionSummaryEntity();
        e1.sessionId = "id-1";
        e1.summary = "text-1";
        CompactionSummaryEntity e2 = new CompactionSummaryEntity();
        e2.sessionId = "id-2";
        e2.summary = "text-2";

        // searchMemory called with topK * 2
        when(compactionRepo.searchMemory(eq("user-1"), eq("query-1"), anyInt()))
                .thenReturn(List.of(e1, e2));

        // Act
        List<ScoredChunk> results = service.search("query-1", "user-1", 1);

        // Assert
        // Requirement 1: Verify RRF logic (reciprocalRankFusion) is never called
        verify(service, never()).reciprocalRankFusion(anyInt(), any());

        // Requirement 2: Result is limited to topK
        assertEquals(1, results.size());

        // Requirement 4: Fast path result order is preserved from FTS
        assertEquals("id-1", results.get(0).chunkId());
    }

    @Test
    void testCallingRrfWhenVectorSearchEnabled() {
        // Arrange
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(compactionRepo.searchMemory(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(globalKb.search(anyString(), anyInt())).thenReturn(List.of());

        // Act
        service.search("query-1", "user-1", 5);

        // Assert
        // Requirement 3: RRF is called
        verify(service, atLeastOnce()).reciprocalRankFusion(eq(5), any(), any());
    }
}
