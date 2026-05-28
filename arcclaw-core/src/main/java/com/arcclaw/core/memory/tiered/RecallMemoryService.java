package com.arcclaw.core.memory.tiered;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.arcclaw.persistence.api.RecallMemoryStorePort;
import com.arcclaw.persistence.api.RecallMessageDto;

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
