package com.arcclaw.core.compaction;

/**
 * Request to trigger an asynchronous context compaction for a session.
 */
public record CompactionRequest(String sessionId, String userId) {}
