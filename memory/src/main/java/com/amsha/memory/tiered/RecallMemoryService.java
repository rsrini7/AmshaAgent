package com.amsha.memory.tiered;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;

@ApplicationScoped
public class RecallMemoryService {

    @Inject
    RecallMemoryStorePort recallStore;

    public void insertOrIgnore(RecallMessageDto message) {
        recallStore.insertOrIgnore(message);
    }

    public void insertBatch(List<RecallMessageDto> messages) {
        recallStore.insertBatch(messages);
    }

    public List<RecallMessageDto> search(RecallSearchQuery query) {
        return recallStore.search(query);
    }

    /**
     * Purge recall messages older than retentionDays for a given user.
     * Returns the number of deleted messages.
     */
    public void purgeExpired(String userId, int retentionDays) {
        recallStore.purgeExpired(userId, retentionDays);
    }

    /**
     * Purge all recall messages older than retentionDays across all users.
     */
    public void purgeExpiredAll(int retentionDays) {
        recallStore.purgeExpiredAll(retentionDays);
    }
}
