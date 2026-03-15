package com.arcclaw.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import com.arcclaw.core.persistence.FtsConnectionInterceptor;
import com.arcclaw.core.state.CompactionSummaryRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Merges FTS5 keyword results and sqlite-vec vector results using
 * Reciprocal Rank Fusion (RRF) with k=60.
 */
@ApplicationScoped
public class TwoTierMemoryService {

    private static final Logger log = Logger.getLogger(TwoTierMemoryService.class);
    private static final double RRF_K = 60.0;

    @Inject FtsConnectionInterceptor ftsInterceptor;
    @Inject CompactionSummaryRepository compactionRepo;
    @Inject GlobalKnowledgeBase globalKb;
    @Inject SessionKnowledgeBase sessionKb;

    /**
     * Search across FTS5 and vector tiers, merged via RRF.
     * Falls back to FTS5-only if sqlite-vec is unavailable.
     */
    public List<ScoredChunk> search(String userId, String query, int topN) {
        // --- FTS5 tier (always available) ---
        List<ScoredChunk> fts5Results = toScoredChunks(
            compactionRepo.searchMemory(userId, query, topN));

        if (!ftsInterceptor.isVectorSearchEnabled()) {
            log.debugf("TwoTierMemoryService: vec0 unavailable, FTS5-only path.");
            return fts5Results.stream().limit(topN).toList();
        }

        // --- Vector tier ---
        List<ScoredChunk> globalResults  = globalKb.search(query, topN);
        List<ScoredChunk> sessionResults = sessionKb.search(query, topN);

        // Merge all three lists via RRF
        return reciprocalRankFusion(topN, fts5Results, globalResults, sessionResults);
    }

    /**
     * Package-private helper for unit testing the RRF logic.
     */
    List<ScoredChunk> merge(List<ScoredChunk> l1, List<ScoredChunk> l2) {
        return reciprocalRankFusion(10, l1, l2);
    }

    @SafeVarargs
    final List<ScoredChunk> reciprocalRankFusion(
            int topN, List<ScoredChunk>... rankedLists) {

        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, ScoredChunk> chunkById = new LinkedHashMap<>();

        for (List<ScoredChunk> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                ScoredChunk chunk = list.get(i);
                int rank = i + 1; // 1-based
                double contribution = 1.0 / (RRF_K + rank);
                rrfScores.merge(chunk.chunkId(), contribution, Double::sum);
                chunkById.merge(chunk.chunkId(), chunk, (existing, incoming) ->
                    (existing.text() == null || existing.text().isBlank()) ? incoming : existing);
            }
        }

        return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topN)
            .map(e -> {
                ScoredChunk orig = chunkById.get(e.getKey());
                return new ScoredChunk(orig.chunkId(), orig.text(), e.getValue());
            })
            .toList();
    }

    private List<ScoredChunk> toScoredChunks(
            List<com.arcclaw.core.state.CompactionSummaryEntity> entities) {
        List<ScoredChunk> result = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            var e = entities.get(i);
            // Score is inverse rank — FTS5 BM25 ranking is ordinal not numeric here
            result.add(new ScoredChunk(e.sessionId, e.summary, 1.0 / (i + 1)));
        }
        return result;
    }
}
