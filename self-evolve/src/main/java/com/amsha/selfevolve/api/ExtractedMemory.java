package com.amsha.selfevolve.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A structured memory record extracted from evolution trace events via rule-based extraction.
 * Immutable once created.
 * <p>
 * {@code sourceEventIdsJson} stores event IDs as a comma-separated string for DB TEXT compatibility.
 * Use {@link #sourceEventIds()} to get the parsed list.
 *
 * @param id                 unique identifier
 * @param sessionId          session this extraction originated from
 * @param userId             user who owns the session
 * @param recordType         type of extracted memory
 * @param content            extracted content text
 * @param confidence         confidence score 0.0-1.0
 * @param contentHash        SHA-256 of content (for deduplication)
 * @param sourceEventIdsJson comma-separated event IDs that led to this extraction
 * @param createdAt          extraction timestamp
 */
public record ExtractedMemory(
        String id,
        String sessionId,
        String userId,
        MemoryRecordType recordType,
        String content,
        double confidence,
        String contentHash,
        String sourceEventIdsJson,
        Instant createdAt) {

    /**
     * Creates a new extracted memory with auto-generated id and timestamp.
     */
    public static ExtractedMemory create(
            String sessionId,
            String userId,
            MemoryRecordType recordType,
            String content,
            double confidence,
            String contentHash,
            List<String> sourceEventIds) {
        String idsJson = sourceEventIds != null ? sourceEventIds.stream().collect(Collectors.joining(",")) : "";
        return new ExtractedMemory(
                UUID.randomUUID().toString(),
                sessionId,
                userId,
                recordType,
                content,
                confidence,
                contentHash,
                idsJson,
                Instant.now());
    }

    /**
     * Returns source event IDs as a parsed list.
     */
    public List<String> sourceEventIds() {
        if (sourceEventIdsJson == null || sourceEventIdsJson.isBlank()) {
            return List.of();
        }
        return List.of(sourceEventIdsJson.split(","));
    }

    public ExtractedMemory {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (recordType == null) {
            throw new IllegalArgumentException("recordType must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0]");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}
