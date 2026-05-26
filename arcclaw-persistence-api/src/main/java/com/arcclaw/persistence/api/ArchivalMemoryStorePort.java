package com.arcclaw.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Port for Archival Memory operations — semantic long-term knowledge with typed chunks,
 * provenance tracing, and access-count-based LRU eviction.
 */
public interface ArchivalMemoryStorePort {

    void upsert(ArchivalChunkDto chunk);

    List<ArchivalChunkDto> search(ArchivalSearchQuery query);

    Optional<ArchivalChunkDto> getById(String chunkId);

    void incrementAccessCount(String chunkId);

    void incrementAccessCounts(List<String> chunkIds);

    void delete(String chunkId);

    void evictLowConfidence(double threshold, int minAgeDays);
}
