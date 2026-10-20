package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.ChatMemoryDto;
import com.amsha.persistence.api.ChatMemoryStorePort;
import com.amsha.persistence.api.PersistenceBackend;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsChatMemoryStoreAdapter implements ChatMemoryStorePort {

    private static final String DOMAIN = "chat-memory";

    @Inject
    FilesystemFileStore store;

    @Override
    public void save(ChatMemoryDto dto) {
        store.write(List.of(DOMAIN, dto.memoryId() + ".json"), dto);
    }

    @Override
    public Optional<ChatMemoryDto> findById(String memoryId) {
        return store.readOptional(List.of(DOMAIN, memoryId + ".json"), ChatMemoryDto.class);
    }

    @Override
    public void deleteById(String memoryId) {
        store.delete(List.of(DOMAIN, memoryId + ".json"));
    }

    @Override
    public List<Long> getTimestamps(String memoryId) {
        return Collections.emptyList();
    }
}
