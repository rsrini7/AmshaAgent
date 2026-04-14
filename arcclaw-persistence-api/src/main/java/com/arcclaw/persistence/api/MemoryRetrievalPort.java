package com.arcclaw.persistence.api;

import java.util.List;

/**
 * Port interface for memory retrieval operations.
 * This interface isolates memory retrieval concerns from the core domain.
 * 
 * Uses transfer objects (DTOs) to avoid circular dependencies:
 * - MemoryChunkDto: search result representation
 * - MemoryEntryDto: memory entry for indexing
 * 
 * Backend adapters translate between these DTOs and their internal domain models.
 */
public interface MemoryRetrievalPort {

    /**
     * Searches for relevant memory chunks based on a query.
     * 
     * @param query the search query
     * @param topK the maximum number of results to return
     * @param userId The user whose memory to search (NOT the session ID).
     * @param ftsSearch whether to use Full Text Search (keyword) instead of vector search
     * @return list of scored memory chunks matching the query
     */
    List<MemoryChunkDto> search(String query, int topK, String userId, boolean ftsSearch);

    /**
     * Indexes a memory entry for future retrieval.
     * 
     * @param entry the memory entry to index
     */
    String index(MemoryEntryDto entry);

    /**
     * Gets the capabilities of the persistence backend.
     * 
     * @return the backend capabilities
     */
    BackendCapabilities capabilities();
}