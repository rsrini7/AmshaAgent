package com.amsha.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Port for Recall Memory operations — exact conversation history with FTS5 searchability.
 * <p>
 * Idempotency is enforced at the database layer via unique constraint on
 * {@code (session_id, turn_number, content_hash)}.
 * Duplicate inserts are safely ignored (INSERT OR IGNORE / ON CONFLICT DO NOTHING).
 */
public interface RecallMemoryStorePort {

    void insertOrIgnore(RecallMessageDto message);

    void insertBatch(List<RecallMessageDto> messages);

    List<RecallMessageDto> search(RecallSearchQuery query);

    Optional<RecallMessageDto> getById(String messageId);

    void purgeExpired(String userId, int retentionDays);
}
