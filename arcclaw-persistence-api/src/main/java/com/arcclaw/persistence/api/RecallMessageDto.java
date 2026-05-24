package com.arcclaw.persistence.api;

import java.time.Instant;

/**
 * Transfer object for a Recall Memory message.
 * Recall stores exact conversation history with FTS5 searchability.
 *
 * @param contentHash SHA-256 of message content, used for DB-enforced idempotency
 * @param messageType one of: USER_MESSAGE, AGENT_MESSAGE, TOOL_CALL, TOOL_RESULT,
 *                    TOOL_ERROR, COMPACTION_SUMMARY, SYSTEM_NOTE
 * @param metadataJson optional extensible metadata — serialized JSON string (nullable)
 */
public record RecallMessageDto(
    String messageId,
    String sessionId,
    String userId,
    int turnNumber,
    String contentHash,
    String role,
    String messageType,
    String content,
    String toolName,
    String toolCallId,
    boolean hasCode,
    boolean hasError,
    Instant createdAt,
    String metadataJson
) {}
