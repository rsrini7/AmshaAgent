package com.arcclaw.core.test;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.arcclaw.persistence.api.ChatMemoryDto;
import com.arcclaw.persistence.api.ChatMemoryStorePort;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockChatMemoryStorePort implements ChatMemoryStorePort {

    private final Map<String, ChatMemoryDto> store = new ConcurrentHashMap<>();

    @Override
    public void save(ChatMemoryDto dto) {
        store.put(dto.memoryId(), dto);
    }

    @Override
    public Optional<ChatMemoryDto> findById(String memoryId) {
        return Optional.ofNullable(store.get(memoryId));
    }

    @Override
    public void deleteById(String memoryId) {
        store.remove(memoryId);
    }

    @Override
    public List<Long> getTimestamps(String memoryId) {
        return Collections.emptyList();
    }
}
