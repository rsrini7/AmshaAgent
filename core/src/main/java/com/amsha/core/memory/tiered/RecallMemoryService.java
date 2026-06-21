package com.amsha.core.memory.tiered;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.RecallMessageDto;

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
}
