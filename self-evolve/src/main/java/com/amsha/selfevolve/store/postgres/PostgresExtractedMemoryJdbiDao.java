package com.amsha.selfevolve.store.postgres;

import java.util.List;

import com.amsha.selfevolve.api.ExtractedMemory;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SQL Object for extracted_memories table (PostgreSQL).
 */
public interface PostgresExtractedMemoryJdbiDao {

    @SqlBatch("""
            INSERT INTO extracted_memories
            (id, session_id, user_id, record_type, content, confidence, content_hash, source_event_ids, created_at)
            VALUES (:id, :sessionId, :userId, :recordType, :content, :confidence, :contentHash, :sourceEventIdsJson, :createdAt)
            ON CONFLICT (session_id, content_hash) DO NOTHING
            """)
    int[] insertBatch(@BindMethods List<ExtractedMemory> memories);

    @SqlQuery("""
            SELECT id, session_id, user_id, record_type, content, confidence, content_hash, source_event_ids, created_at
            FROM extracted_memories
            WHERE session_id = :sessionId
            ORDER BY created_at DESC
            LIMIT :limit
            """)
    List<ExtractedMemory> getBySession(@Bind("sessionId") String sessionId, @Bind("limit") int limit);

    @SqlQuery("""
            SELECT id, session_id, user_id, record_type, content, confidence, content_hash, source_event_ids, created_at
            FROM extracted_memories
            WHERE user_id = :userId
            ORDER BY created_at DESC
            LIMIT :limit
            """)
    List<ExtractedMemory> getByUser(@Bind("userId") String userId, @Bind("limit") int limit);

    @SqlQuery("""
            SELECT id, session_id, user_id, record_type, content, confidence, content_hash, source_event_ids, created_at
            FROM extracted_memories
            WHERE record_type = :recordType
            ORDER BY confidence DESC, created_at DESC
            LIMIT :limit
            """)
    List<ExtractedMemory> getByType(@Bind("recordType") String recordType, @Bind("limit") int limit);

    @SqlQuery("""
            SELECT COUNT(*) > 0
            FROM extracted_memories
            WHERE session_id = :sessionId AND content_hash = :contentHash
            """)
    boolean existsByHash(@Bind("sessionId") String sessionId, @Bind("contentHash") String contentHash);

    @SqlUpdate("""
            DELETE FROM extracted_memories
            WHERE created_at < NOW() - CAST(:retentionDays || ' days' AS INTERVAL)
            """)
    int purgeExpired(@Bind("retentionDays") int retentionDays);
}
