package com.arcclaw.persistence.api;

import java.time.Instant;

/**
 * Transfer object for an Archival Memory chunk.
 * Archival provides semantic long-term knowledge with typed chunks, provenance, and access tracking.
 *
 * @param chunkType one of: CODE, DOC, PATTERN, FACT, PREFERENCE, DIRECTIVE
 * @param tags comma-separated or JSON array string for extensible filtering (nullable)
 * @param confidence 0.0-1.0; auto-extracted chunks typically < 0.7, human-reviewed = 1.0
 * @param accessCount monotonically incremented on retrieval; used for LRU eviction
 */
public record ArchivalChunkDto(
    String chunkId,
    String userId,
    String content,
    String chunkType,
    String sourceSession,
    String sourceMessage,
    String tags,
    double confidence,
    int accessCount,
    Instant createdAt,
    Instant updatedAt
) {}
