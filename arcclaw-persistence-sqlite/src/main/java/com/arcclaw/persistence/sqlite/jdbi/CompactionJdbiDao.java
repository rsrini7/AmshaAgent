package com.arcclaw.persistence.sqlite.jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.CompactionSummaryDto;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(CompactionSummaryDto.class)
public interface CompactionJdbiDao {

    @SqlQuery("SELECT session_id AS \"sessionId\", summary, compacted_at AS \"compactedAt\", messages_compacted AS \"messagesCompacted\", user_id AS \"userId\", tenant_id AS \"tenantId\" FROM compaction_summary WHERE session_id = :sessionId")
    Optional<CompactionSummaryDto> findById(@Bind("sessionId") String sessionId);

    @SqlUpdate("INSERT OR REPLACE INTO compaction_summary (session_id, summary, compacted_at, messages_compacted, user_id) VALUES (:sessionId, :summary, :compactedAt, :messagesCompacted, :userId)")
    void upsert(@Bind("sessionId") String sessionId, @Bind("summary") String summary, @Bind("compactedAt") Instant compactedAt, @Bind("messagesCompacted") int messagesCompacted, @Bind("userId") String userId);

    @SqlUpdate("DELETE FROM compaction_summary WHERE session_id = :sessionId")
    void deleteBySessionId(@Bind("sessionId") String sessionId);

    @SqlUpdate("DELETE FROM fts.compaction_summary_fts WHERE sessionId = :sessionId")
    void deleteFtsBySessionId(@Bind("sessionId") String sessionId);

    @SqlUpdate("INSERT INTO fts.compaction_summary_fts(sessionId, summary) VALUES (:sessionId, :summary)")
    void upsertFts(@Bind("sessionId") String sessionId, @Bind("summary") String summary);

    @SqlQuery("SELECT cs.session_id AS \"sessionId\", cs.summary, cs.compacted_at AS \"compactedAt\", cs.messages_compacted AS \"messagesCompacted\", cs.user_id AS \"userId\", cs.tenant_id AS \"tenantId\" FROM compaction_summary cs JOIN fts.compaction_summary_fts fts_v ON cs.session_id = fts_v.sessionId WHERE fts_v.summary MATCH :query AND cs.user_id = :userId ORDER BY rank LIMIT :limit")
    List<CompactionSummaryDto> search(@Bind("query") String query, @Bind("userId") String userId, @Bind("limit") int limit);
}
