package com.arcclaw.persistence.sqlite;

import java.util.ArrayList;
import java.util.List;

import com.arcclaw.persistence.api.MemoryChunkDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TwoTierMemoryServiceRrfTest {

    @Test
    void testRrfMerging() {
        SqliteMemoryRetrievalAdapter adapter = new SqliteMemoryRetrievalAdapter();

        // FTS5 results: Doc A (rank 1), Doc B (rank 2)
        List<MemoryChunkDto> fts5 = new ArrayList<>();
        fts5.add(new MemoryChunkDto("doc-a", "content-a", 1.0));
        fts5.add(new MemoryChunkDto("doc-b", "content-b", 0.9));

        // Vector results: Doc C (rank 1), Doc A (rank 2)
        List<MemoryChunkDto> vector = new ArrayList<>();
        vector.add(new MemoryChunkDto("doc-c", "content-c", 0.99));
        vector.add(new MemoryChunkDto("doc-a", "content-a", 0.95));

        List<MemoryChunkDto> merged = adapter.reciprocalRankFusion(10, fts5, vector);

        // Expected RRF Scores (k=60):
        // Doc A: 1/(60+1) [FTS] + 1/(60+2) [Vec] = 0.01639 + 0.01612 = 0.03251
        // Doc C: 0 [FTS] + 1/(60+1) [Vec] = 0.01639
        // Doc B: 1/(60+2) [FTS] + 0 [Vec] = 0.01612

        Assertions.assertEquals(3, merged.size());
        Assertions.assertEquals("doc-a", merged.get(0).chunkId());
        Assertions.assertEquals("doc-c", merged.get(1).chunkId());
        Assertions.assertEquals("doc-b", merged.get(2).chunkId());
        
        Assertions.assertTrue(merged.get(0).score() > merged.get(1).score());
        Assertions.assertTrue(merged.get(1).score() > merged.get(2).score());
    }
}
