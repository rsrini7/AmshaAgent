package com.amsha.core.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle-owned storage for REST test-data-collection expected labels.
 *
 * <p>Cleared from {@link WorkspaceManager#terminateSession} and from normal
 * agent-run completion in {@code AgentSessionService}.
 */
@ApplicationScoped
public class SessionTestLabelStore {

    private final ConcurrentHashMap<String, Map<String, String>> labelsBySession = new ConcurrentHashMap<>();

    public void put(String sessionId, Map<String, String> labels) {
        if (sessionId == null || labels == null || labels.isEmpty()) {
            return;
        }
        labelsBySession.put(sessionId, Map.copyOf(labels));
    }

    public String get(String sessionId, String task) {
        Map<String, String> labels = labelsBySession.get(sessionId);
        return labels != null ? labels.get(task) : null;
    }

    public void clearSession(String sessionId) {
        if (sessionId != null) {
            labelsBySession.remove(sessionId);
        }
    }

    /** Test helper: whether any labels remain for the session. */
    boolean hasSession(String sessionId) {
        return labelsBySession.containsKey(sessionId);
    }
}
