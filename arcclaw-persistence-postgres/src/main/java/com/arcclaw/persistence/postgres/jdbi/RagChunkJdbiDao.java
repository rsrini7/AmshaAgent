package com.arcclaw.persistence.postgres.jdbi;

import java.time.Instant;
import java.util.List;

import com.arcclaw.persistence.api.MemoryChunkDto;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface RagChunkJdbiDao {

    @SqlUpdate("INSERT INTO rag_chunk (chunk_id, text, source, created_at) VALUES (:chunkId, :text, :source, :createdAt) ON CONFLICT (chunk_id) DO NOTHING")
    void insert(
        @Bind("chunkId") String chunkId,
        @Bind("text") String text,
        @Bind("source") String source,
        @Bind("createdAt") Instant createdAt
    );

    @SqlQuery("SELECT chunk_id AS \"chunkId\", text, ts_rank(to_tsvector('english', text), plainto_tsquery('english', :query)) AS score FROM rag_chunk WHERE to_tsvector('english', text) @@ plainto_tsquery('english', :query) ORDER BY score DESC LIMIT :limit")
    @RegisterConstructorMapper(MemoryChunkDto.class)
    List<MemoryChunkDto> search(@Bind("query") String query, @Bind("limit") int limit);
}
