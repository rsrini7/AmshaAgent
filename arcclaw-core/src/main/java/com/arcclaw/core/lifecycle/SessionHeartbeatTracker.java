package com.arcclaw.core.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active session liveness via heartbeats.
 */
@ApplicationScoped
public class SessionHeartbeatTracker {

    private static final Logger log = Logger.getLogger(SessionHeartbeatTracker.class);

    /** Active session heartbeats: sessionId → last heartbeat timestamp. */
    private final ConcurrentHashMap<String, Instant> heartbeats = new ConcurrentHashMap<>();

    /**
     * Record a heartbeat for a session — proves the session is still alive.
     *
     * @param sessionId The workspace session ID.
     */
    public void recordHeartbeat(String sessionId) {
        heartbeats.put(sessionId, Instant.now());
        log.debugf("Heartbeat recorded for session=%s", sessionId);
    }

    /**
     * Remove a session from heartbeat tracking.
     */
    public void removeHeartbeat(String sessionId) {
        heartbeats.remove(sessionId);
    }

    /**
     * Get the number of tracked sessions (for testing/monitoring).
     */
    public int activeSessionCount() {
        return heartbeats.size();
    }

    /**
     * Clear all tracked sessions (for testing).
     */
    public void clearAll() {
        heartbeats.clear();
    }

    /**
     * Inject a heartbeat with a specific timestamp for testing.
     *
     * @param sessionId The workspace session ID.
     * @param timestamp The heartbeat timestamp.
     */
    public void injectHeartbeat(String sessionId, Instant timestamp) {
        heartbeats.put(sessionId, timestamp);
        log.debugf("Heartbeat injected for session=%s timestamp=%s", sessionId, timestamp);
    }

    /**
     * Iterate over all sessions whose last heartbeat is before the cutoff,
     * invoking the action for each expired sessionId.
     * Safe for concurrent modification — ConcurrentHashMap.forEach() is used internally.
     */
    public void forEachExpired(java.time.Instant cutoff, java.util.function.Consumer<String> action) {
        heartbeats.forEach((sessionId, lastHeartbeat) -> {
            if (lastHeartbeat.isBefore(cutoff)) {
                action.accept(sessionId);
            }
        });
    }
}
