package com.amsha.selfevolve.retrieve;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;

/**
 * Simple retriever for extracted memories (Phase 9).
 * Provides query-by-type, query-by-session, and query-by-user access
 * to previously extracted memories.
 */
@Singleton
public class SimpleMemoryRetriever {

    private final ExtractedMemoryStorePort store;

    @Inject
    public SimpleMemoryRetriever(ExtractedMemoryStorePort store) {
        this.store = store;
    }

    /**
     * Retrieves extracted memories by record type, ordered by confidence descending.
     */
    public List<ExtractedMemory> getByType(MemoryRecordType type, int limit) {
        if (type == null || limit <= 0) {
            return List.of();
        }
        return store.getByType(type, limit);
    }

    /**
     * Retrieves extracted memories for a session.
     */
    public List<ExtractedMemory> getBySession(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        return store.getBySession(sessionId, limit);
    }

    /**
     * Retrieves extracted memories for a user across all sessions.
     */
    public List<ExtractedMemory> getByUser(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) {
            return List.of();
        }
        return store.getByUser(userId, limit);
    }
}
