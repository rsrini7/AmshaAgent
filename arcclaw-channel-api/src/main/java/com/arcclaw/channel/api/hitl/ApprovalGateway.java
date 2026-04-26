package com.arcclaw.channel.api.hitl;

/**
 * SPI contract for HITL approval decisions.
 * Channel modules depend on this interface only - never on the core impl.
 * §9.1
 */
public interface ApprovalGateway {
    ApprovalResult decide(String sessionId, String toolName,
                          ApprovalDecision decision, String reason);

    /**
     * Resolves a potentially truncated session ID prefix to the full session ID.
     * Channels like Telegram that suffer from strict payload byte limits can use this
     * to safely recover the full context ID from a shortened version.
     */
    default String resolveSessionIdPrefix(String prefix) {
        return prefix;
    }
}
