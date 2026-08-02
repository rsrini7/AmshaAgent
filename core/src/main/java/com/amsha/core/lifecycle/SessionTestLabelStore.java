package com.amsha.core.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle-owned storage for REST test-data-collection expected labels.
 *
 * <p>Cleared from {@link WorkspaceManager#terminateSession} and from normal
 * agent-run completion in {@code AgentSessionService}; labels remain across
 * completed turns so multi-turn classifier scenarios retain their labels.
 */
@ApplicationScoped
public class SessionTestLabelStore {

    private record Entry(Map<String, String> labels, Instant lastTouched) {}

    private final ConcurrentHashMap<String, Entry> labelsBySession = new ConcurrentHashMap<>();

    public void put(String sessionId, Map<String, String> labels) {
        if (sessionId == null || labels == null || labels.isEmpty()) {
            return;
        }
        labelsBySession.put(sessionId, new Entry(Map.copyOf(labels), Instant.now()));
    }

    public String get(String sessionId, String task) {
        Entry entry = labelsBySession.computeIfPresent(
                sessionId, (key, current) -> new Entry(current.labels(), Instant.now()));
        return entry != null ? entry.labels().get(task) : null;
    }

    public void clearSession(String sessionId) {
        if (sessionId != null) {
            labelsBySession.remove(sessionId);
        }
    }

    /** Removes labels that have not been used since the supplied cutoff. */
    public void reapExpired(Instant cutoff) {
        if (cutoff == null) {
            return;
        }
        labelsBySession.forEach((sessionId, entry) -> {
            if (entry.lastTouched().isBefore(cutoff)) {
                labelsBySession.remove(sessionId, entry);
            }
        });
    }

    /** Test helper: whether any labels remain for the session. */
    boolean hasSession(String sessionId) {
        return labelsBySession.containsKey(sessionId);
    }
}
