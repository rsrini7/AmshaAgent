package com.arcclaw.persistence.api;

import java.time.Instant;

public record CompactionSummaryDto(
        String sessionId, String summary, Instant compactedAt, Integer messagesCompacted, String userId) {}
