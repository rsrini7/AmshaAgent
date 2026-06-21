package com.amsha.core.audit;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates atomic sequencing and HMAC chaining for session audits.
 * Prevents TOCTOU races between concurrent tool executions on the same session.
 */
@ApplicationScoped
public class SessionAuditSequencer {
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    /**
     * Retrieves or creates a fair ReentrantLock for the given session.
     */
    public ReentrantLock lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, _ -> new ReentrantLock(true));
    }

    /**
     * Removes the lock reference for a reaped or terminal session.
     * Called by SessionReaper to prevent map growth.
     */
    public void releaseStaleSession(String sessionId) {
        sessionLocks.remove(sessionId);
    }
}
