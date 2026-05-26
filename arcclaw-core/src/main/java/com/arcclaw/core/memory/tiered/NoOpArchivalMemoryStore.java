package com.arcclaw.core.memory.tiered;

import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.ArchivalChunkDto;
import com.arcclaw.persistence.api.ArchivalMemoryStorePort;
import com.arcclaw.persistence.api.ArchivalSearchQuery;

/**
 * No-op Archival Memory store returned when tiered memory or Archival is disabled.
 */
final class NoOpArchivalMemoryStore implements ArchivalMemoryStorePort {

    @Override
    public void upsert(ArchivalChunkDto chunk) {
        // no-op when disabled
    }

    @Override
    public List<ArchivalChunkDto> search(ArchivalSearchQuery query) {
        return List.of();
    }

    @Override
    public Optional<ArchivalChunkDto> getById(String chunkId) {
        return Optional.empty();
    }

    @Override
    public void incrementAccessCount(String chunkId) {
        // no-op when disabled
    }

    @Override
    public void incrementAccessCounts(List<String> chunkIds) {
        // no-op when disabled
    }

    @Override
    public void delete(String chunkId) {
        // no-op when disabled
    }

    @Override
    public void evictLowConfidence(double threshold, int minAgeDays) {
        // no-op when disabled
    }
}
