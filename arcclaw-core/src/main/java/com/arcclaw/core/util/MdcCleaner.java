package com.arcclaw.core.util;

import org.jboss.logging.MDC;

/**
 * Standard utility for clearing diagnostic context in finally blocks.
 * Ensures that thread-local logging metadata does not leak across requests.
 * §8.1
 */
public final class MdcCleaner {

    private MdcCleaner() {}

    /**
     * Exhaustively clears all known MDC keys defined in MdcKeys.
     */
    public static void clearAll() {
        MDC.remove(MdcKeys.USER_ID);
        MDC.remove(MdcKeys.SESSION_ID);
        MDC.remove(MdcKeys.CHAT_ID);
        MDC.remove(MdcKeys.AGENT_ROLE);
        MDC.remove(MdcKeys.ROLE);
        MDC.remove(MdcKeys.PARALLEL_BATCH_SIZE);
        MDC.remove(MdcKeys.PARALLEL_TOOLS);
    }

    /**
     * Removes a specific subset of MDC keys.
     * Use when clearing ALL would be too broad (e.g. nested scopes
     * where outer context keys must be preserved).
     */
    public static void clear(String... keys) {
        if (keys != null) {
            for (String key : keys) {
                MDC.remove(key);
            }
        }
    }
}
