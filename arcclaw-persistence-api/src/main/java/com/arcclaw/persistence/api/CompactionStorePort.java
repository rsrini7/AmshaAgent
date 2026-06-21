package com.arcclaw.persistence.api;

import java.util.List;
import java.util.Optional;

public interface CompactionStorePort {
    Optional<CompactionSummaryDto> findLatestBySessionId(String sessionId);

    void upsert(CompactionSummaryDto dto);

    void deleteBySessionId(String sessionId);

    List<CompactionSummaryDto> search(String userId, String query, int limit);
}
