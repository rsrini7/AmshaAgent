package com.arcclaw.core.scope;

import com.arcclaw.core.security.AutonomyLevel;
import jakarta.enterprise.inject.Vetoed;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the context of a workspace session.
 * Managed as a @WorkspaceScoped bean via producer.
 */
@Vetoed // Managed via Producer to handle no-arg constraint and initialization
public class WorkspaceContext {

    private final String sessionId;
    private AutonomyLevel autonomyLevel = AutonomyLevel.READONLY;
    private final Map<String, Object> attributes = new HashMap<>();

    public WorkspaceContext(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    public void setAutonomyLevel(AutonomyLevel autonomyLevel) {
        this.autonomyLevel = autonomyLevel;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}
