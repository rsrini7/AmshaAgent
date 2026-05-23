package com.arcclaw.persistence.sqlite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.persistence.api.BackendCapabilities;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.api.IngestResult;
import com.arcclaw.persistence.api.MemoryChunkDto;
import com.arcclaw.persistence.api.MemoryEntryDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import com.arcclaw.persistence.common.SessionKnowledgeBase;
import io.quarkus.arc.properties.IfBuildProperty;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * SQLite implementation of {@link MemoryRetrievalPort}.
 * Merges FTS5 keyword results and sqlite-vec vector results using Reciprocal Rank Fusion (RRF).
 */
@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteMemoryRetrievalAdapter implements MemoryRetrievalPort {

    private static final double RRF_K = 60.0;

    @Inject
    GlobalKnowledgeBase globalKb;

    @Inject
    Instance<SessionKnowledgeBase> sessionKb;

    @Inject
    CompactionStorePort compactionRepo;

    @Inject
    FtsConnectionInterceptor ftsInterceptor;

    @ConfigProperty(name = "arcclaw.rag.max-source-chars", defaultValue = "400")
    int maxSourceChars;

    @Override
    public List<MemoryChunkDto> search(String query, int topK, String userId) {
        // 1. Perform FTS Search
        List<CompactionSummaryDto> ftsEntities = compactionRepo.search(userId, query, topK * 2);
        List<MemoryChunkDto> ftsResults = new ArrayList<>();
        for (int i = 0; i < ftsEntities.size(); i++) {
            var e = ftsEntities.get(i);
            ftsResults.add(new MemoryChunkDto(e.sessionId(), e.summary(), 1.0 / (i + 1)));
        }

        // 2. Fast path: skip RRF if vector search is disabled
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            return ftsResults.stream().limit(topK).toList();
        }

        // 3. Perform Vector Search
        List<MemoryChunkDto> vectorResults = new ArrayList<>();
        vectorResults.addAll(globalKb.search(query, topK * 2));
        
        // Session-scoped vector search (if applicable)
        if (!sessionKb.isUnsatisfied() && WorkspaceScopeHolder.isActive()) {
            sessionKb.get().search(query, topK * 2).forEach(c ->
                vectorResults.add(new MemoryChunkDto(c.chunkId(), c.text(), c.score())));
        }

        // Sort combined vector results to create a ranked list for RRF
        vectorResults.sort(Comparator.comparingDouble(MemoryChunkDto::score).reversed());

        // 4. Merge via RRF
        List<MemoryChunkDto> mergedResults = reciprocalRankFusion(topK, ftsResults, vectorResults);

        // 5. Truncate Pass
        return mergedResults.stream()
            .map(chunk -> {
                String text = chunk.text();
                if (text != null && text.length() > maxSourceChars) {
                    text = text.substring(0, maxSourceChars) + "…";
                }
                return new MemoryChunkDto(chunk.chunkId(), text, chunk.score());
            })
            .toList();
    }

    @Override
    public IngestResult index(MemoryEntryDto entry) {
        String chunkId = globalKb.ingest(entry.text());
        String fallbackId = entry.id() != null ? "session-" + entry.id() : UUID.randomUUID().toString();
        if (!sessionKb.isUnsatisfied()) {
            sessionKb.get().ingest(
                chunkId != null ? chunkId : fallbackId,
                entry.text());
        }
        return new IngestResult(chunkId != null ? chunkId : fallbackId, chunkId == null);
    }

    @Override
    public BackendCapabilities capabilities() {
        return new BackendCapabilities(
            true, // supportsFullTextSearch
            ftsInterceptor.isVectorSearchEnabled(),
            false, // supportsAdvisoryLocks
            false  // supportsNestedTransactions
        );
    }

    /**
     * RRF merge: score(d) = sum(1.0 / (k + rank)) across all ranked lists.
     */
    @SafeVarargs
    final List<MemoryChunkDto> reciprocalRankFusion(int topN, List<MemoryChunkDto>... rankedLists) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, MemoryChunkDto> chunkById = new LinkedHashMap<>();

        for (List<MemoryChunkDto> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                MemoryChunkDto chunk = list.get(i);
                int rank = i + 1;
                double contribution = 1.0 / (RRF_K + rank);
                rrfScores.merge(chunk.chunkId(), contribution, Double::sum);
                // Prefer non-empty text
                chunkById.merge(chunk.chunkId(), chunk, (existing, incoming) ->
                    (existing.text() == null || existing.text().isBlank()) ? incoming : existing);
            }
        }

        return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topN)
            .map(e -> {
                MemoryChunkDto orig = chunkById.get(e.getKey());
                return new MemoryChunkDto(orig.chunkId(), orig.text(), e.getValue());
            })
            .toList();
    }
}
