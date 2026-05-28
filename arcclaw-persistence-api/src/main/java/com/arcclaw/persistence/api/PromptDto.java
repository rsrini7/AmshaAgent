package com.arcclaw.persistence.api;

import java.time.Instant;

public record PromptDto(
        String id,
        String roleId,
        String sectionKey,
        String content,
        int version,
        String status,
        int rolloutPercentage,
        String auditMetadata,
        Instant createdAt,
        Instant updatedAt) {}
