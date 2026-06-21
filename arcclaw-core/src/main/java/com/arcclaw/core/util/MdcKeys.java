package com.arcclaw.core.util;

import java.util.List;

/**
 * Centered constants for MDC (Mapped Diagnostic Context) keys used in logging.
 */
public final class MdcKeys {
    private MdcKeys() {}

    public static final String USER_ID = "userId";
    public static final String SESSION_ID = "sessionId";
    public static final String CHAT_ID = "chatId";
    public static final String AGENT_ROLE = "agentRole";
    public static final String ROLE = "role";
    /** Unique request correlation ID set by ApiKeyAuthFilter. */
    public static final String REQUEST_ID = "requestId";

    /** Number of tools in the current parallel batch. */
    public static final String PARALLEL_BATCH_SIZE = "parallelBatchSize";

    /** Comma-separated tool names in current parallel batch. */
    public static final String PARALLEL_TOOLS = "parallelTools";

    /** All known ArcClaw MDC keys for safe cleanup. */
    public static final List<String> ALL_KEYS =
            List.of(USER_ID, SESSION_ID, CHAT_ID, AGENT_ROLE, ROLE, REQUEST_ID, PARALLEL_BATCH_SIZE, PARALLEL_TOOLS);
}
