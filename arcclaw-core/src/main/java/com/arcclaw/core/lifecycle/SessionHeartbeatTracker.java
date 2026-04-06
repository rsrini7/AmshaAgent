package com.arcclaw.core.lifecycle;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.security.AutonomyLevel;
import org.jboss.logging.Logger;

/**
 * Tracks active session liveness via heartbeats.
 */
@ApplicationScoped
public class SessionHeartbeatTracker {

    private static final Logger log = Logger.getLogger(SessionHeartbeatTracker.class);

    public record SessionMetadata(
        Instant lastHeartbeat,
        AutonomyLevel autonomyLevel,
        String ownerKeyName,
        long totalInputTokens,
        long totalOutputTokens
    ) {}

    /** Active session metadata: sessionId → last heartbeat + autonomyLevel. */
    private final ConcurrentHashMap<String, SessionMetadata> sessionData = new ConcurrentHashMap<>();

    /**
     * Record a heartbeat for a session — proves the session is still alive.
     *
     * @param sessionId The workspace session ID.
     */
    public void recordHeartbeat(String sessionId) {
        recordHeartbeat(sessionId, AutonomyLevel.SUPERVISED);
    }

    /**
     * Record a heartbeat with an explicit autonomy level (e.g. from REST create).
     */
    public void recordHeartbeat(String sessionId, AutonomyLevel level) {
        // Preserve existing owner — heartbeat ticks must NOT erase ownership
        SessionMetadata existing = sessionData.get(sessionId);
        String existingOwner = (existing != null) ? existing.ownerKeyName() : null;
        long existingIn  = (existing != null) ? existing.totalInputTokens()  : 0L;
        long existingOut = (existing != null) ? existing.totalOutputTokens() : 0L;
        sessionData.put(sessionId, new SessionMetadata(Instant.now(), level, existingOwner, existingIn, existingOut));
        log.debugf("Heartbeat recorded for session=%s autonomy=%s", sessionId, level);
    }

    /**
     * Remove a session from heartbeat tracking.
     */
    public void removeHeartbeat(String sessionId) {
        sessionData.remove(sessionId);
    }

    /**
     * Returns all currently tracked session IDs with their last heartbeat timestamps.
     */
    public Map<String, Instant> activeSessions() {
        return sessionData.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().lastHeartbeat()
                ));
    }

    /**
     * Returns true if the session is currently being tracked.
     */
    public boolean exists(String sessionId) {
        return sessionData.containsKey(sessionId);
    }

    /**
     * Gets the autonomy level for a session.
     */
    public AutonomyLevel getAutonomyLevel(String sessionId) {
        SessionMetadata data = sessionData.get(sessionId);
        return data != null ? data.autonomyLevel() : null;
    }

    /**
     * Get the number of tracked sessions (for testing/monitoring).
     */
    public int activeSessionCount() {
        return sessionData.size();
    }

    /**
     * Clear all tracked sessions (for testing).
     */
    public void clearAll() {
        sessionData.clear();
    }

    /**
     * Inject a heartbeat with a specific timestamp for testing.
     *
     * @param sessionId The workspace session ID.
     * @param timestamp The heartbeat timestamp.
     */
    public void injectHeartbeat(String sessionId, Instant timestamp) {
        SessionMetadata existing = sessionData.get(sessionId);
        String existingOwner = (existing != null) ? existing.ownerKeyName() : null;
        long existingIn  = (existing != null) ? existing.totalInputTokens()  : 0L;
        long existingOut = (existing != null) ? existing.totalOutputTokens() : 0L;
        sessionData.put(sessionId, new SessionMetadata(timestamp, AutonomyLevel.SUPERVISED, existingOwner, existingIn, existingOut));
        log.debugf("Heartbeat injected for session=%s timestamp=%s", sessionId, timestamp);
    }

    /**
     * Bind an API key identity as owner of the given session.
     * Uses computeIfPresent: if the session was already reaped, this silently no-ops.
     */
    public void bindOwner(String sessionId, String ownerKeyName) {
        sessionData.computeIfPresent(sessionId, (k, existing) ->
            new SessionMetadata(existing.lastHeartbeat(), existing.autonomyLevel(), ownerKeyName, 
            existing.totalInputTokens(), existing.totalOutputTokens() ));
        log.debugf("Owner bound for session=%s owner=%s", sessionId, ownerKeyName);
    }

    public void recordTokens(String sessionId, long inputTokens, long outputTokens) {
        sessionData.computeIfPresent(sessionId, (k, existing) ->
            new SessionMetadata(
                existing.lastHeartbeat(),
                existing.autonomyLevel(),
                existing.ownerKeyName(),
                inputTokens,
                outputTokens
            )
        );
    }

    public long[] getTokens(String sessionId) {
        SessionMetadata data = sessionData.get(sessionId);
        return data != null
            ? new long[]{data.totalInputTokens(), data.totalOutputTokens()}
            : new long[]{0L, 0L};
    }

    /**
     * Returns the owner key name for the given session, if one was bound.
     */
    public Optional<String> getOwner(String sessionId) {
        SessionMetadata data = sessionData.get(sessionId);
        return (data != null) ? Optional.ofNullable(data.ownerKeyName()) : Optional.empty();
    }

    /**
     * Iterate over all sessions whose last heartbeat is before the cutoff,
     * invoking the action for each expired sessionId.
     * Safe for concurrent modification — ConcurrentHashMap.forEach() is used internally.
     */
    public void forEachExpired(Instant cutoff, Consumer<String> action) {
        sessionData.forEach((sessionId, metadata) -> {
            if (metadata.lastHeartbeat().isBefore(cutoff)) {
                action.accept(sessionId);
            }
        });
    }
}
