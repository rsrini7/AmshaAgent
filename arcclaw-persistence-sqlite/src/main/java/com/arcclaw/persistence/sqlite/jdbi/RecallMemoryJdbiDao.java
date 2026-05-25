package com.arcclaw.persistence.sqlite.jdbi;

import java.util.List;

import com.arcclaw.persistence.api.RecallMessageDto;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
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
}
