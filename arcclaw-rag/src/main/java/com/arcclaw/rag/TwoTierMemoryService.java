package com.arcclaw.rag;

import com.arcclaw.core.persistence.FtsConnectionInterceptor;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CompactionSummaryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Merges FTS5 keyword results and sqlite-vec vector results using
 * Reciprocal Rank Fusion (RRF) with k=60.
 *
 * RRF formula: score(d) = sum over each ranked list of: 1.0 / (k + rank)
 * where rank is 1-based position in that list.
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
     *
     * @param userId  tenant-scoped identifier (chatId or sessionId as fallback)
     * @param query   search query
     * @param topN    max results to return
     */
    public List<ScoredChunk> search(String userId, String query, int topN) {
        // --- FTS5 tier (always available) ---
        // compactionRepo.searchMemory filters by userId — if userId is a
        // sessionId fallback it may return no FTS results, which is fine.
        // Vector tier below will still contribute.
        List<ScoredChunk> fts5Results = toScoredChunks(
            compactionRepo.searchMemory(userId, query, topN));

        if (!ftsInterceptor.isVectorSearchEnabled()) {
            log.debugf("TwoTierMemoryService: vec0 unavailable, FTS5-only path.");
            return fts5Results.stream().limit(topN).toList();
        }

        // --- Vector tier ---
        List<ScoredChunk> globalResults = globalKb.search(query, topN);

        // SessionKnowledgeBase is @WorkspaceScoped — guard against inactive scope
        List<ScoredChunk> sessionResults = List.of();
        try {
            if (WorkspaceScopeHolder.isActive()) {
                sessionResults = sessionKb.search(query, topN);
            }
        } catch (Exception e) {
            log.warnf("TwoTierMemoryService: SessionKnowledgeBase unavailable: %s",
                e.getMessage());
        }

        return reciprocalRankFusion(topN, fts5Results, globalResults, sessionResults);
    }

    /**
     * RRF merge: score(d) = sum(1.0 / (k + rank)) across all ranked lists.
     * Package-private for unit testing.
     */
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
                // Prefer non-empty text when merging duplicate chunkIds
                chunkById.merge(chunk.chunkId(), chunk, (existing, incoming) ->
                    (existing.text() == null || existing.text().isBlank())
                        ? incoming : existing);
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

    /**
     * Helper method exposed for unit tests (merge two lists with default topN).
     */
    List<ScoredChunk> merge(List<ScoredChunk> l1, List<ScoredChunk> l2) {
        return reciprocalRankFusion(10, l1, l2);
    }

    private List<ScoredChunk> toScoredChunks(
            List<com.arcclaw.core.state.CompactionSummaryEntity> entities) {
        List<ScoredChunk> result = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            var e = entities.get(i);
            result.add(new ScoredChunk(e.sessionId, e.summary, 1.0 / (i + 1)));
        }
        return result;
    }
}