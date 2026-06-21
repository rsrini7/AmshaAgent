package com.amsha.core.test;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Collections;
import java.util.List;

import com.amsha.persistence.api.BackendCapabilities;
import com.amsha.persistence.api.IngestResult;
import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.MemoryEntryDto;
import com.amsha.persistence.api.MemoryRetrievalPort;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockMemoryRetrievalPort implements MemoryRetrievalPort {

    @Override
    public List<MemoryChunkDto> search(String query, int topK, String userId) {
        return Collections.emptyList();
    }

    @Override
    public IngestResult index(MemoryEntryDto entry) {
        return new IngestResult("mock-id", false);
    }

    @Override
    public BackendCapabilities capabilities() {
        return new BackendCapabilities(false, false, false, false);
    }
}
