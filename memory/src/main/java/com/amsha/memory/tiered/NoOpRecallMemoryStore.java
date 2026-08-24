package com.amsha.memory.tiered;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;

/**
 * No-op Recall Memory store returned when tiered memory or Recall is disabled.
 */
final class NoOpRecallMemoryStore implements RecallMemoryStorePort {

    @Override
    public void insertOrIgnore(RecallMessageDto message) {
        // no-op when disabled
    }

    @Override
    public void insertBatch(List<RecallMessageDto> messages) {
        // no-op when disabled
    }

    @Override
    public List<RecallMessageDto> search(RecallSearchQuery query) {
        return List.of();
    }

    @Override
    public Optional<RecallMessageDto> getById(String messageId) {
        return Optional.empty();
    }

    @Override
    public void purgeExpired(String userId, int retentionDays) {
        // no-op when disabled
    }

    @Override
    public void purgeExpiredAll(int retentionDays) {
        // no-op when disabled
    }
}
