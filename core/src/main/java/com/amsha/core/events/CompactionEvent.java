package com.amsha.core.events;

/**
 * CDI event fired when compaction occurs.
 * Observed by self-evolve module for trace capture.
 */
public record CompactionEvent(String sessionId, String userId, int compactedCount, int retainedCount, String summary) {}
