package com.amsha.persistence.postgres.jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CompactionSummaryDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(CompactionSummaryDto.class)
public interface CompactionJdbiDao {

    @SqlQuery(
            "SELECT session_id AS \"sessionId\", summary, compacted_at AS \"compactedAt\", messages_compacted AS \"messagesCompacted\", user_id AS \"userId\" FROM compaction_summary WHERE session_id = :sessionId")
    Optional<CompactionSummaryDto> findById(@Bind("sessionId") String sessionId);

    @SqlUpdate(
            "INSERT INTO compaction_summary (session_id, summary, compacted_at, messages_compacted, user_id) VALUES (:sessionId, :summary, :compactedAt, :messagesCompacted, :userId) ON CONFLICT (session_id) DO UPDATE SET summary = EXCLUDED.summary, compacted_at = EXCLUDED.compacted_at, messages_compacted = EXCLUDED.messages_compacted, user_id = COALESCE(EXCLUDED.user_id, compaction_summary.user_id)")
    void upsert(
            @Bind("sessionId") String sessionId,
            @Bind("summary") String summary,
            @Bind("compactedAt") Instant compactedAt,
            @Bind("messagesCompacted") int messagesCompacted,
            @Bind("userId") String userId);

    @SqlUpdate("DELETE FROM compaction_summary WHERE session_id = :sessionId")
    void deleteBySessionId(@Bind("sessionId") String sessionId);

    @SqlQuery(
            "SELECT session_id AS \"sessionId\", summary, compacted_at AS \"compactedAt\", messages_compacted AS \"messagesCompacted\", user_id AS \"userId\" FROM compaction_summary WHERE user_id = :userId AND to_tsvector('english', summary) @@ plainto_tsquery('english', :query) LIMIT :limit")
    List<CompactionSummaryDto> search(
            @Bind("userId") String userId, @Bind("query") String query, @Bind("limit") int limit);
}
