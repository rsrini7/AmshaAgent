package com.arcclaw.persistence.sqlite.jdbi;

import java.util.List;

import com.arcclaw.persistence.api.RecallMessageDto;
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
        ON CONFLICT(session_id, turn_number, content_hash) DO NOTHING
        """)
    void insertOrIgnore(@BindMethods RecallMessageDto dto);

    @SqlBatch("""
        INSERT INTO recall_messages
        (message_id, session_id, user_id, turn_number, content_hash, role, message_type,
         content, tool_name, tool_call_id, has_code, has_error, created_at, metadata_json)
        VALUES (:messageId, :sessionId, :userId, :turnNumber, :contentHash, :role, :messageType,
                :content, :toolName, :toolCallId, :hasCode, :hasError, :createdAt, :metadataJson)
        ON CONFLICT(session_id, turn_number, content_hash) DO NOTHING
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
        SELECT m.message_id AS "messageId", m.session_id AS "sessionId", m.user_id AS "userId", 
               m.turn_number AS "turnNumber", m.content_hash AS "contentHash", m.role, 
               m.message_type AS "messageType", m.content, m.tool_name AS "toolName", 
               m.tool_call_id AS "toolCallId", m.has_code AS "hasCode", m.has_error AS "hasError", 
               m.created_at AS "createdAt", m.metadata_json AS "metadataJson"
        FROM recall_messages m
        JOIN recall_messages_fts fts ON m.rowid = fts.rowid
        WHERE m.user_id = :userId
          AND (:sessionId IS NULL OR m.session_id = :sessionId)
          AND (:includeSummaries = true OR m.message_type != 'COMPACTION_SUMMARY')
          AND recall_messages_fts MATCH :query
        ORDER BY 
            CASE WHEN :includeSummaries = true AND m.message_type = 'COMPACTION_SUMMARY' THEN 1 ELSE 0 END,
            rank
        LIMIT :limit
        """)
    List<RecallMessageDto> search(@Bind("userId") String userId,
                                  @Bind("sessionId") String sessionId,
                                  @Bind("query") String query,
                                  @Bind("includeSummaries") boolean includeSummaries,
                                  @Bind("limit") int limit);
}
