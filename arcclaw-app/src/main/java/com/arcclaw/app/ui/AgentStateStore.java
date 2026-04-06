package com.arcclaw.app.ui;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import com.arcclaw.core.agent.event.AgentStateChangedEvent;

/**
 * In-memory store for current agent statuses.
 * Listens to AgentStateChangedEvent from arcclaw-core.
 */
@ApplicationScoped
public class AgentStateStore {

    private final Map<String, AgentState> currentStates = new ConcurrentHashMap<>();

    public void onAgentStateChanged(@Observes AgentStateChangedEvent event) {
        String key = event.sessionId() + ":" + event.agentName();
        currentStates.put(key, new AgentState(
            event.agentName(),
            event.newStatus(),
            event.currentTask()
        ));
    }

    public Collection<AgentState> getAll() {
        return currentStates.values();
    }

    public record AgentState(String name, String status, String currentTask) {}
}
