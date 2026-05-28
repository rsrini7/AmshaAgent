package com.arcclaw.persistence.api;

import java.util.Set;

/**
 * Immutable query object for Archival Memory search.
 * <p>
 * Supports BM25-only, vector-only, and hybrid search modes via
 * {@code useVector} and {@code useBM25} flags.
 */
public record ArchivalSearchQuery(
        String userId,
        String query,
        int limit,
        Set<String> chunkTypes,
        boolean useVector,
        boolean useBM25,
        float[] queryEmbedding) {}
