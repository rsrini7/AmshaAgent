package com.amsha.channel.api.hitl;

/**
 * SPI contract for HITL approval decisions.
 * Channel modules depend on this interface only - never on the core impl.
 * §9.1
 */
public interface ApprovalGateway {
    ApprovalResult decide(String sessionId, String toolName, ApprovalDecision decision, String reason);

    /**
     * Returns true while the run for {@code sessionId} is still actively executing after an
     * approval (i.e. the session is resuming). Channels use this to surface live progress
     * (e.g. a Telegram "typing" action) without coupling to {@code com.amsha.core.state}
     * internals - this is the ArchUnit-safe way to query resume state from a channel module.
     */
    boolean isSessionResuming(String sessionId);

    /**
     * Resolves a potentially truncated session ID prefix to the full session ID.
     * Channels like Telegram that suffer from strict payload byte limits can use this
     * to safely recover the full context ID from a shortened version.
     */
    default String resolveSessionIdPrefix(String prefix) {
        return prefix;
    }
}
