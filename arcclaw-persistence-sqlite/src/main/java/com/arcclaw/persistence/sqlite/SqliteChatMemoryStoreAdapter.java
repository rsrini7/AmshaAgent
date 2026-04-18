package com.arcclaw.persistence.sqlite;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.ChatMemoryEntity;
import com.arcclaw.persistence.api.ChatMemoryDto;
import com.arcclaw.persistence.api.ChatMemoryStorePort;
import io.quarkus.arc.Unremovable;

/**
 * SQLite implementation of ChatMemoryStorePort.
 * §1.2: All JPA logic moved from core to here.
 */
@ApplicationScoped
@Unremovable
public class SqliteChatMemoryStoreAdapter implements ChatMemoryStorePort {

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
    @Transactional(Transactional.TxType.SUPPORTS)
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
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Long> getTimestamps(String memoryId) {
        // ChatMemoryEntity currently doesn't store per-message timestamps in a queryable way
        // (they are likely inside the messagesJson). Returning empty to satisfy API for now.
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
