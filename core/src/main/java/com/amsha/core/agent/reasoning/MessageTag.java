package com.amsha.core.agent.reasoning;

/**
 * Marker prefix for AI compliance/approval injection messages written
 * into ChatMemoryStore. Used by LangChainReasoningEngine and
 * PersistentChatMemoryStore to filter these entries during context
 * reconstruction.
 *
 * STABILITY: This constant is persisted in SQLite. It must NEVER be
 * changed once any data has been written with it. Changing it causes
 * all previously tagged messages to pass through the filter unstripped.
 *
 * BACKWARD COMPATIBILITY: Messages written before Phase 48 do not carry
 * this prefix. They pass through the filter (isTagged returns false) -
 * this is correct. The regex-based filter in PersistentChatMemoryStore
 * continues to handle pre-Phase-48 patterns during the transition period.
 *
 * IMPORTANT: AiMessage.text() can return null for tool-use messages.
 * isTagged() must always null-check before calling startsWith().
 */
public final class MessageTag {

    private MessageTag() {}

    /** Stable across restarts - must never change once data exists in SQLite. */
    public static final String COMPLIANCE_PREFIX = "[AMSHA:2f4a9c]";

    /** Wraps content with the compliance prefix for storage. */
    public static String tag(String content) {
        return COMPLIANCE_PREFIX + " " + content;
    }

    /**
     * Returns true if the message text starts with the compliance prefix.
     * Safe for null input - returns false for null or blank content.
     */
    public static boolean isTagged(String content) {
        return content != null && content.startsWith(COMPLIANCE_PREFIX);
    }
}
