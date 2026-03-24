package com.arcclaw.rag;

import java.util.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.persistence.FtsConnectionInterceptor;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CompactionSummaryEntity;
import com.arcclaw.core.state.CompactionSummaryRepository;
import org.jboss.logging.Logger;

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
     * @param query   search query
     * @param userId  tenant-scoped identifier (chatId or sessionId as fallback)
     * @param topK    max results to return
     */
    public List<ScoredChunk> search(String query, String userId, int topK) {
        List<ScoredChunk> ftsResults = ftsSearch(query, userId, topK * 2);

        // Fast path: skip RRF entirely when vector search is disabled.
        // RRF is a fusion algorithm — running it over a single list adds CPU with zero benefit.
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            return ftsResults.stream().limit(topK).toList();
        }

        // Both sources active: use RRF for reciprocal rank fusion
        List<ScoredChunk> vectorResults = vectorSearch(query, userId, topK * 2);
        return mergeRrf(ftsResults, vectorResults, topK);
    }

    /**
     * Performs keyword search via FTS5.
     */
    private List<ScoredChunk> ftsSearch(String query, String userId, int topK) {
        return toScoredChunks(compactionRepo.searchMemory(userId, query, topK));
    }

    /**
     * Performs vector search via global and session knowledge bases.
     */
    private List<ScoredChunk> vectorSearch(String query, String userId, int topK) {
        List<ScoredChunk> globalResults = globalKb.search(query, topK);
        List<ScoredChunk> sessionResults = List.of();
        try {
            if (WorkspaceScopeHolder.isActive()) {
                sessionResults = sessionKb.search(query, topK);
            }
        } catch (Exception e) {
            log.warnf("TwoTierMemoryService: SessionKnowledgeBase unavailable: %s", e.getMessage());
        }

        if (sessionResults.isEmpty()) {
            return globalResults;
        }

        List<ScoredChunk> combined = new ArrayList<>(globalResults);
        combined.addAll(sessionResults);
        // Sort by score to provide a single ranked list for RRF
        combined.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return combined;
    }

    /**
     * Merges multiple ranked lists using Reciprocal Rank Fusion.
     */
    private List<ScoredChunk> mergeRrf(List<ScoredChunk> fts, List<ScoredChunk> vec, int topK) {
        return reciprocalRankFusion(topK, fts, vec);
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
        return mergeRrf(l1, l2, 10);
    }

    private List<ScoredChunk> toScoredChunks(
            List<CompactionSummaryEntity> entities) {
        List<ScoredChunk> result = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            var e = entities.get(i);
            result.add(new ScoredChunk(e.getSessionId(), e.getSummary(), 1.0 / (i + 1)));
        }
        return result;
    }
}
