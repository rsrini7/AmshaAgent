package com.arcclaw.persistence.api;

import java.util.Set;

/**
 * Immutable query object for Recall Memory search.
 * <p>
 * Encapsulates all search criteria to prevent parameter bloat and boolean blindness.
 * Default search excludes {@code COMPACTION_SUMMARY} records — set
 * {@code includeSummaries} to true for overview queries.
 */
public record RecallSearchQuery(
    String userId,
    String query,
    String sessionId,
    int limit,
    boolean includeSummaries,
    Set<String> messageTypes
) {
    public boolean isCrossSession() {
        return sessionId == null;
    }
}
