package com.arcclaw.core.test;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockCompactionStorePort implements CompactionStorePort {

    @Override
    public Optional<CompactionSummaryDto> findLatestBySessionId(String sessionId) {
        return Optional.empty();
    }

    @Override
    public void upsert(CompactionSummaryDto dto) {}

    @Override
    public void deleteBySessionId(String sessionId) {}

    @Override
    public List<CompactionSummaryDto> search(String userId, String query, int limit) {
        return Collections.emptyList();
    }
}
