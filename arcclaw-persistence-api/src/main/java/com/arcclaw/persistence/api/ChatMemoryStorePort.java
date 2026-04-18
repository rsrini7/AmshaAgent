package com.arcclaw.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Port for persisting and managing chat memory.
 * §1.2: Core-persistence boundary.
 */
public interface ChatMemoryStorePort {
    void save(ChatMemoryDto dto);
    Optional<ChatMemoryDto> findById(String memoryId);
    void deleteById(String memoryId);
    List<Long> getTimestamps(String memoryId);
}
