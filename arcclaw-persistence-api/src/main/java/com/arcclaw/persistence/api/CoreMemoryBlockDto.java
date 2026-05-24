package com.arcclaw.persistence.api;

import java.time.Instant;

/**
 * Transfer object for a Core Memory block.
 * Core Memory blocks are small, curated context blocks always rendered into the system prompt.
 */
public record CoreMemoryBlockDto(
    String blockId,
    String userId,
    String blockKey,
    String content,
    int version,
    Instant createdAt,
    Instant updatedAt
) {}
