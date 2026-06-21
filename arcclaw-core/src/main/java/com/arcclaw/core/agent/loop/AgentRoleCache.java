package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.arcclaw.core.agent.event.AgentStateChangedEvent;
import com.arcclaw.core.state.StatefulAgentStore;

@ApplicationScoped
public class AgentRoleCache {

    @Inject
    StatefulAgentStore stateStore;

    private final ConcurrentHashMap<String, String> lastRoleBySession = new ConcurrentHashMap<>();

    public void onStateChanged(@Observes AgentStateChangedEvent event) {
        if (event.sessionId() != null && event.agentName() != null) {
            lastRoleBySession.put(event.sessionId(), event.agentName());
        }
    }

    public Optional<String> findLatestRole(String sessionId) {
        String inMemory = lastRoleBySession.get(sessionId);
        if (inMemory != null) {
            return Optional.of(inMemory);
        }

        // Fallback to database if memory was lost (e.g. following a restart)
        Optional<String> persistent = stateStore.findLatestRole(sessionId);
        persistent.ifPresent(role -> lastRoleBySession.put(sessionId, role));
        return persistent;
    }

    public void evict(String sessionId) {
        lastRoleBySession.remove(sessionId);
    }
}
