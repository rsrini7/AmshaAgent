package com.arcclaw.persistence.postgres.jdbi;

import java.util.Optional;

import com.arcclaw.persistence.api.ChatMemoryDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(ChatMemoryDto.class)
public interface ChatMemoryJdbiDao {

    @SqlUpdate(
            "INSERT INTO chat_memory (memory_id, messages_json, metadata_json) VALUES (:memoryId, :messagesJson, :metadataJson) ON CONFLICT (memory_id) DO UPDATE SET messages_json = EXCLUDED.messages_json, metadata_json = EXCLUDED.metadata_json")
    void save(@BindMethods ChatMemoryDto dto);

    @SqlQuery(
            "SELECT memory_id AS \"memoryId\", messages_json AS \"messagesJson\", metadata_json AS \"metadataJson\" FROM chat_memory WHERE memory_id = :memoryId")
    Optional<ChatMemoryDto> findById(@Bind("memoryId") String memoryId);

    @SqlUpdate("DELETE FROM chat_memory WHERE memory_id = :memoryId")
    void deleteById(@Bind("memoryId") String memoryId);
}
