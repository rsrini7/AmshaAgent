package com.amsha.persistence.api;

/**
 * Transfer object representing a scored memory chunk for search results.
 * This is a minimal DTO that does not depend on heavy domain types.
 *
 * Adapters may translate between this DTO and their internal domain models
 * (e.g., ScoredChunk in amsha-rag).
 */
public record MemoryChunkDto(String chunkId, String text, double score) {}
