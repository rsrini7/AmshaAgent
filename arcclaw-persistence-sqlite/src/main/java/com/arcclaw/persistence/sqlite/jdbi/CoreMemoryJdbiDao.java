package com.arcclaw.persistence.sqlite.jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.CoreMemoryBlockDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(CoreMemoryBlockDto.class)
public interface CoreMemoryJdbiDao {

    String CORE_MEMORY_COLUMNS = """
        block_id AS "blockId",
        user_id AS "userId",
        block_key AS "blockKey",
        content,
        version,
        created_at AS "createdAt",
        updated_at AS "updatedAt"
        """;

    @SqlQuery("SELECT " + CORE_MEMORY_COLUMNS + " FROM core_memory_blocks WHERE user_id = :userId")
    List<CoreMemoryBlockDto> findByUser(@Bind("userId") String userId);

    @SqlQuery("SELECT " + CORE_MEMORY_COLUMNS
            + " FROM core_memory_blocks WHERE user_id = :userId AND block_key = :blockKey")
    Optional<CoreMemoryBlockDto> findByKey(@Bind("userId") String userId, @Bind("blockKey") String blockKey);

    @SqlUpdate("""
        INSERT INTO core_memory_blocks (block_id, user_id, block_key, content, version, created_at, updated_at)
        VALUES (:blockId, :userId, :blockKey, :content, 1, :now, :now)
        ON CONFLICT(user_id, block_key) DO UPDATE SET
            content = :content,
            version = version + 1,
            updated_at = :now
        WHERE version = :expectedVersion
        """)
    int upsert(
            @Bind("blockId") String blockId,
            @Bind("userId") String userId,
            @Bind("blockKey") String blockKey,
            @Bind("content") String content,
            @Bind("expectedVersion") int expectedVersion,
            @Bind("now") Instant now);

    @SqlUpdate("DELETE FROM core_memory_blocks WHERE user_id = :userId AND block_key = :blockKey")
    void delete(@Bind("userId") String userId, @Bind("blockKey") String blockKey);
}
