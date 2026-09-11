package com.amsha.core.state;

import java.util.Optional;

import com.amsha.tool.api.AgentSentinels;

/**
 * Relationship helpers for the session tree rooted at a chat/workspace session:
 * the root session plus its {@code <root>:delegate:<agent>:<n>} children created
 * by {@code delegate_to_agent} and group consultation.
 *
 * <p>Live-status, busy-message and resume-refresh logic all need to answer "does
 * this checkpoint belong to that conversation?". Centralising the answer here
 * keeps the {@code :delegate:} sentinel out of call sites.
 */
public final class SessionTree {

    private SessionTree() {}

    /**
     * {@code true} when {@code candidateSessionId} is the root session itself or
     * one of its delegated descendants.
     */
    public static boolean contains(String rootSessionId, String candidateSessionId) {
        if (rootSessionId == null || candidateSessionId == null) {
            return false;
        }
        return candidateSessionId.equals(rootSessionId) || isDelegatedChild(rootSessionId, candidateSessionId);
    }

    /** {@code true} when {@code candidateSessionId} is a strict delegated child of {@code rootSessionId}. */
    public static boolean isDelegatedChild(String rootSessionId, String candidateSessionId) {
        if (rootSessionId == null || candidateSessionId == null) {
            return false;
        }
        return candidateSessionId.startsWith(rootSessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL);
    }

    /** The parent session of a delegated child; empty for a root session. */
    public static Optional<String> parentOf(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        int idx = sessionId.indexOf(AgentSentinels.DELEGATE_SESSION_SENTINEL);
        return idx > 0 ? Optional.of(sessionId.substring(0, idx)) : Optional.empty();
    }

    /** The root session of a delegated child or the session itself if already root. */
    public static String rootOf(String sessionId) {
        return parentOf(sessionId).orElse(sessionId);
    }
}
