package com.amsha.rag;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.MemoryRetrievalPort;

// import org.jboss.logging.Logger;

/**
 * Merges FTS5 keyword results and sqlite-vec vector results.
 * Delegated to the persistence layer via {@link MemoryRetrievalPort}.
 */
@ApplicationScoped
public class TwoTierMemoryService {

    // private static final Logger log = Logger.getLogger(TwoTierMemoryService.class);

    @Inject
    MemoryRetrievalPort memoryPort;

    @Inject
    RerankingService rerankingService;

    /**
     * Search across keyword and vector tiers.
     *
     * @param query   search query
     * @param userId  tenant-scoped identifier
     * @param topK    max results to return
     */
    public List<ScoredChunk> search(String query, String userId, int topK) {
        List<MemoryChunkDto> results = memoryPort.search(query, topK, userId);
        return results.stream()
                .map(dto -> new ScoredChunk(dto.chunkId(), dto.text(), dto.score()))
                .toList();
    }

    /**
     * Search across keyword and vector tiers, then reranks candidates using {@link RerankingService}.
     *
     * @param query   search query
     * @param userId  tenant-scoped identifier
     * @param topK    max candidate results to retrieve and rerank
     * @return reranked chunks sorted by rerank score descending
     */
    public List<RerankedChunk> searchReranked(String query, String userId, int topK) {
        List<ScoredChunk> candidates = search(query, userId, topK);
        return rerankingService.rerank(query, candidates);
    }
}
