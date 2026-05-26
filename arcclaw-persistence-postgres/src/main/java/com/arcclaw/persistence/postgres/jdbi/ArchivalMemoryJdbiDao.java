package com.arcclaw.persistence.postgres.jdbi;

import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.ArchivalChunkDto;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(ArchivalChunkDto.class)
public interface ArchivalMemoryJdbiDao {

    @SqlUpdate("""
        INSERT INTO archival_chunks
        (chunk_id, user_id, content, chunk_type, source_session, source_message,
         tags, confidence, access_count, created_at, updated_at)
        VALUES (:chunkId, :userId, :content, :chunkType, :sourceSession, :sourceMessage,
                :tags, :confidence, :accessCount, :createdAt, :updatedAt)
        ON CONFLICT(chunk_id) DO UPDATE SET
            content = EXCLUDED.content,
            chunk_type = EXCLUDED.chunk_type,
            source_session = EXCLUDED.source_session,
            source_message = EXCLUDED.source_message,
            tags = EXCLUDED.tags,
            confidence = EXCLUDED.confidence,
            updated_at = EXCLUDED.updated_at
        """)
    void upsert(@BindMethods ArchivalChunkDto dto);

    @SqlQuery("""
        SELECT chunk_id AS "chunkId", user_id AS "userId", content, chunk_type AS "chunkType",
               source_session AS "sourceSession", source_message AS "sourceMessage",
               tags, confidence, access_count AS "accessCount",
               created_at AS "createdAt", updated_at AS "updatedAt"
        FROM archival_chunks
        WHERE user_id = :userId
          AND (:chunkTypesEmpty = true OR chunk_type IN (<chunkTypes>))
          AND content_tsv @@ plainto_tsquery('english', :query)
        ORDER BY ts_rank(content_tsv, plainto_tsquery('english', :query)) DESC
        LIMIT :limit
        """)
    List<ArchivalChunkDto> search(@Bind("userId") String userId,
                                  @Bind("query") String query,
                                  @Bind("limit") int limit,
                                  @Bind("chunkTypesEmpty") boolean chunkTypesEmpty,
                                  @Bind("chunkTypes") List<String> chunkTypes);

    @SqlQuery("""
        SELECT chunk_id AS "chunkId", user_id AS "userId", content, chunk_type AS "chunkType",
               source_session AS "sourceSession", source_message AS "sourceMessage",
               tags, confidence, access_count AS "accessCount",
               created_at AS "createdAt", updated_at AS "updatedAt"
        FROM archival_chunks WHERE chunk_id = :chunkId
        """)
    Optional<ArchivalChunkDto> getById(@Bind("chunkId") String chunkId);

    @SqlQuery("""
        SELECT chunk_id AS "chunkId", user_id AS "userId", content, chunk_type AS "chunkType",
               source_session AS "sourceSession", source_message AS "sourceMessage",
               tags, confidence, access_count AS "accessCount",
               created_at AS "createdAt", updated_at AS "updatedAt"
        FROM archival_chunks WHERE chunk_id IN (<chunkIds>)
        """)
    List<ArchivalChunkDto> getByIds(@BindList("chunkIds") List<String> chunkIds);

    @SqlUpdate("UPDATE archival_chunks SET access_count = access_count + 1 WHERE chunk_id = :chunkId")
    void incrementAccessCount(@Bind("chunkId") String chunkId);

    @SqlBatch("UPDATE archival_chunks SET access_count = access_count + 1 WHERE chunk_id = :chunkId")
    void incrementAccessCounts(@Bind("chunkId") List<String> chunkIds);

    @SqlUpdate("DELETE FROM archival_chunks WHERE chunk_id = :chunkId")
    void delete(@Bind("chunkId") String chunkId);
}
