package com.amsha.selfevolve.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A single trace event captured during agent interaction.
 * Immutable once created — no setters, all fields final.
 *
 * @param eventId       unique event identifier (UUID)
 * @param sessionId     session this event belongs to
 * @param userId        user who owns the session
 * @param eventType     type of interaction event
 * @param role          agent role at time of event (e.g. coordinator, coder)
 * @param content       message content, tool args, or tool result
 * @param toolName      tool name for TOOL_CALL/RESULT/ERROR events, null otherwise
 * @param turnNumber    monotonic turn counter within session
 * @param metadataJson  extensible JSON metadata
 * @param createdAt     event timestamp (ISO-8601)
 */
public record EvolutionEvent(
        String eventId,
        String sessionId,
        String userId,
        EvolutionEventType eventType,
        String role,
        String content,
        String toolName,
        Integer turnNumber,
        String metadataJson,
        Instant createdAt) {
    /**
     * Creates a new event with auto-generated id and timestamp.
     */
    public static EvolutionEvent create(
            String sessionId,
            String userId,
            EvolutionEventType eventType,
            String role,
            String content,
            String toolName,
            Integer turnNumber,
            String metadataJson) {
        return new EvolutionEvent(
                UUID.randomUUID().toString(),
                sessionId,
                userId,
                eventType,
                role,
                content,
                toolName,
                turnNumber,
                metadataJson,
                Instant.now());
    }

    /**
     * Compact constructor — validates required fields.
     */
    public EvolutionEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}
