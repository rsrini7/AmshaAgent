package com.amsha.persistence.postgres.jdbi;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.RecallMessageDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(RecallMessageDto.class)
public interface RecallMemoryJdbiDao {

    @SqlUpdate("""
        INSERT INTO recall_messages
        (message_id, session_id, user_id, turn_number, content_hash, role, message_type,
         content, tool_name, tool_call_id, has_code, has_error, created_at, metadata_json)
        VALUES (:messageId, :sessionId, :userId, :turnNumber, :contentHash, :role, :messageType,
                :content, :toolName, :toolCallId, :hasCode, :hasError, :createdAt, :metadataJson)
        ON CONFLICT (session_id, turn_number, content_hash) DO NOTHING
        """)
    void insertOrIgnore(@BindMethods RecallMessageDto dto);

    @SqlBatch("""
        INSERT INTO recall_messages
        (message_id, session_id, user_id, turn_number, content_hash, role, message_type,
         content, tool_name, tool_call_id, has_code, has_error, created_at, metadata_json)
        VALUES (:messageId, :sessionId, :userId, :turnNumber, :contentHash, :role, :messageType,
                :content, :toolName, :toolCallId, :hasCode, :hasError, :createdAt, :metadataJson)
        ON CONFLICT (session_id, turn_number, content_hash) DO NOTHING
        """)
    void insertBatch(@BindMethods List<RecallMessageDto> messages);

    /**
     * Unified search method.
     * - If sessionId is provided: Session-scoped search.
     * - If sessionId is null: Cross-session search (scoped to userId).
     * - includeSummaries=false (default) excludes COMPACTION_SUMMARY.
     * - includeSummaries=true includes summaries but ranks them lower.
     */
    @SqlQuery("""
        SELECT message_id AS "messageId", session_id AS "sessionId", user_id AS "userId",
               turn_number AS "turnNumber", content_hash AS "contentHash", role,
               message_type AS "messageType", content, tool_name AS "toolName",
               tool_call_id AS "toolCallId", has_code AS "hasCode", has_error AS "hasError",
               created_at AS "createdAt", metadata_json AS "metadataJson"
        FROM recall_messages
        WHERE user_id = :userId
          AND (:sessionId IS NULL OR session_id = :sessionId)
          AND (:includeSummaries = true OR message_type != 'COMPACTION_SUMMARY')
          AND content_tsv @@ plainto_tsquery('english', :query)
        ORDER BY
            CASE WHEN :includeSummaries = true AND message_type = 'COMPACTION_SUMMARY' THEN 1 ELSE 0 END,
            ts_rank(content_tsv, plainto_tsquery('english', :query)) DESC
        LIMIT :limit
        """)
    List<RecallMessageDto> search(
            @Bind("userId") String userId,
            @Bind("sessionId") String sessionId,
            @Bind("query") String query,
            @Bind("includeSummaries") boolean includeSummaries,
            @Bind("limit") int limit);

    @SqlQuery("""
        SELECT message_id AS "messageId", session_id AS "sessionId", user_id AS "userId",
               turn_number AS "turnNumber", content_hash AS "contentHash", role,
               message_type AS "messageType", content, tool_name AS "toolName",
               tool_call_id AS "toolCallId", has_code AS "hasCode", has_error AS "hasError",
               created_at AS "createdAt", metadata_json AS "metadataJson"
        FROM recall_messages WHERE message_id = :messageId
        """)
    Optional<RecallMessageDto> getById(@Bind("messageId") String messageId);

    @SqlUpdate("""
        DELETE FROM recall_messages
        WHERE user_id = :userId
          AND created_at < NOW() - INTERVAL '1 day' * :retentionDays
        """)
    int purgeExpired(@Bind("userId") String userId, @Bind("retentionDays") int retentionDays);

    @SqlUpdate("""
        DELETE FROM recall_messages
        WHERE created_at < NOW() - INTERVAL '1 day' * :retentionDays
        """)
    int purgeExpiredAll(@Bind("retentionDays") int retentionDays);

    @SqlQuery("""
        SELECT COUNT(*) FROM recall_messages WHERE user_id = :userId
        """)
    long countByUserId(@Bind("userId") String userId);
}
