package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CheckpointSessionPort {

    List<CheckpointDto> findActiveBySession(String sessionId);

    Optional<CheckpointDto> findLatestBySession(String sessionId);

    boolean hasPendingApproval(String sessionId);

    void markCheckpointsFailed(String sessionId);

    void purgeOldChatHistory(Instant cutoff);
}
