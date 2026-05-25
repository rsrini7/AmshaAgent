package com.arcclaw.core.memory.tiered;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.ArchivalChunkDto;
import com.arcclaw.persistence.api.ArchivalMemoryStorePort;
import com.arcclaw.persistence.api.ArchivalSearchQuery;

/**
 * Thin domain service for Archival Memory operations.
 * Delegates to {@link ArchivalMemoryStorePort} — no business logic beyond pass-through.
 */
@ApplicationScoped
public class ArchivalMemoryService {

    @Inject
    ArchivalMemoryStorePort store;

    /**
     * Upsert a chunk into the relational archival store.
     * <p>
     * <b>Non-atomic with vector ingestion:</b> The relational row and the vec_archival
     * embedding are written independently. Callers that need vector search must also
     * call {@code VectorIngestionService.ingestVector(chunkId, embedding)} separately.
     * If one succeeds and the other fails, BM25 search still works — the system
     * degrades gracefully. Phase 6 tool path must handle this two-step pattern.
     */
    public void upsert(ArchivalChunkDto chunk) {
        store.upsert(chunk);
    }

    public List<ArchivalChunkDto> search(ArchivalSearchQuery query) {
        return store.search(query);
    }

    public Optional<ArchivalChunkDto> getById(String chunkId) {
        return store.getById(chunkId);
    }

    public void delete(String chunkId) {
        store.delete(chunkId);
    }
}
