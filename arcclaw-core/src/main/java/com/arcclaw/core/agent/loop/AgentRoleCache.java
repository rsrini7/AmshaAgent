package com.arcclaw.core.agent.loop;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.arcclaw.core.agent.event.AgentStateChangedEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class AgentRoleCache {

    private final ConcurrentHashMap<String, String> lastRoleBySession 
        = new ConcurrentHashMap<>();

    public void onStateChanged(@Observes AgentStateChangedEvent event) {
        if (event.sessionId() != null && event.agentName() != null) {
            lastRoleBySession.put(event.sessionId(), event.agentName());
        }
    }

    public Optional<String> findLatestRole(String sessionId) {
        return Optional.ofNullable(lastRoleBySession.get(sessionId));
    }

    public void evict(String sessionId) {
        lastRoleBySession.remove(sessionId);
    }
}
