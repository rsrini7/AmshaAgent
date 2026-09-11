package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CheckpointSessionPort {

    List<CheckpointDto> findActiveBySession(String sessionId);

    List<CheckpointDto> findActiveBySessionTree(String rootSessionId);

    Optional<CheckpointDto> findLatestBySession(String sessionId);

    boolean hasPendingApproval(String sessionId);

    void markCheckpointsFailed(String sessionId);

    void purgeOldChatHistory(Instant cutoff);

    List<CheckpointDto> findByStatusAndSessionIds(String status, List<String> sessionIds, int limit, int offset);

    long countByStatusAndSessionIds(String status, List<String> sessionIds);
}
