package com.arcclaw.persistence.postgres;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.ChatMemoryDto;
import com.arcclaw.persistence.api.ChatMemoryStorePort;
import com.arcclaw.persistence.common.entity.ChatMemoryEntity;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of ChatMemoryStorePort.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresChatMemoryStoreAdapter implements ChatMemoryStorePort {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public void save(ChatMemoryDto dto) {
        ChatMemoryEntity entity = new ChatMemoryEntity(
            dto.memoryId(),
            dto.messagesJson(),
            dto.metadataJson()
        );
        em.merge(entity);
    }

    @Override
    @Transactional
    public Optional<ChatMemoryDto> findById(String memoryId) {
        em.clear();
        ChatMemoryEntity entity = em.find(ChatMemoryEntity.class, memoryId);
        return Optional.ofNullable(entity).map(this::toDto);
    }

    @Override
    @Transactional
    public void deleteById(String memoryId) {
        em.clear();
        ChatMemoryEntity entity = em.find(ChatMemoryEntity.class, memoryId);
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Override
    @Transactional
    public List<Long> getTimestamps(String memoryId) {
        return Collections.emptyList();
    }

    private ChatMemoryDto toDto(ChatMemoryEntity entity) {
        return new ChatMemoryDto(
            entity.getMemoryId(),
            entity.getMessagesJson(),
            entity.getMetadataJson()
        );
    }
}
