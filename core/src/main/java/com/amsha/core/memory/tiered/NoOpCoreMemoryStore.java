package com.amsha.core.memory.tiered;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.CoreMemoryStorePort;

/**
 * No-op Core Memory store returned when tiered memory or Core Memory is disabled.
 */
final class NoOpCoreMemoryStore implements CoreMemoryStorePort {

    @Override
    public List<CoreMemoryBlockDto> read(String userId) {
        return List.of();
    }

    @Override
    public void upsert(String userId, String blockKey, String content, int expectedVersion) {
        // no-op when disabled
    }

    @Override
    public void delete(String userId, String blockKey) {
        // no-op when disabled
    }

    @Override
    public Optional<CoreMemoryBlockDto> get(String userId, String blockKey) {
        return Optional.empty();
    }
}
