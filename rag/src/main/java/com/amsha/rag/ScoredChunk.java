package com.amsha.rag;

/**
 * Represents a chunk retrieved from a memory tier.
 * <p>
 * D2.2/F25: The 'score' field is purely synthetic. It is populated temporarily
 * during Reciprocal Rank Fusion (RRF) in TwoTierMemoryService to merge results
 * from FTS5 keyword search and sqlite-vec vector search.
 * It does NOT represent a raw BM25 or cosine similarity score and MUST NOT
 * be used as an absolute threshold for relevance cutoffs. It is intentionally
 * unused outside the fusion algorithm.
 */
public record ScoredChunk(String chunkId, String text, double score) {}
