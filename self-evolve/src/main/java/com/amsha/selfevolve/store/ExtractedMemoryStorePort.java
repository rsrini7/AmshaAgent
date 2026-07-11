package com.amsha.selfevolve.store;

import java.util.List;

import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;

/**
 * Persistence port for extracted memories from rule-based extraction (Phase 9).
 * Implementations: SQLite, PostgreSQL, Filesystem.
 * NoOp implementation is returned when self-evolve is disabled.
 */
public interface ExtractedMemoryStorePort {

    /**
     * Persists a batch of extracted memories. Uses INSERT OR IGNORE for DB-enforced deduplication.
     *
     * @param memories non-empty list of memories to persist
     * @return number of memories actually persisted
     */
    int insertBatch(List<ExtractedMemory> memories);

    /**
     * Retrieves extracted memories for a session, ordered by creation time descending.
     */
    List<ExtractedMemory> getBySession(String sessionId, int limit);

    /**
     * Retrieves extracted memories for a user across all sessions.
     */
    List<ExtractedMemory> getByUser(String userId, int limit);

    /**
     * Retrieves extracted memories of a specific type.
     */
    List<ExtractedMemory> getByType(MemoryRecordType recordType, int limit);

    /**
     * Lists distinct user IDs with extracted memories.
     *
     * @param limit maximum number of user IDs to return. A value <= 0 represents no limit.
     */
    List<String> listUserIds(int limit);

    /**
     * Checks if a memory with the given content hash already exists for a session.
     */
    boolean existsByHash(String sessionId, String contentHash);

    /**
     * Purges extracted memories older than specified retention days.
     */
    int purgeExpired(int retentionDays);
}
