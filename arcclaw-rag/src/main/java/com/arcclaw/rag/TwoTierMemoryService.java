package com.arcclaw.rag;

import java.util.List;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import com.arcclaw.persistence.api.MemoryChunkDto;
// import org.jboss.logging.Logger;

/**
 * Merges FTS5 keyword results and sqlite-vec vector results.
 * Delegated to the persistence layer via {@link MemoryRetrievalPort}.
 */
@ApplicationScoped
public class TwoTierMemoryService {

    // private static final Logger log = Logger.getLogger(TwoTierMemoryService.class);

    @Inject MemoryRetrievalPort memoryPort;

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
}
