package com.amsha.app.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.amsha.core.agent.event.AgentStateChangedEvent;

/**
 * In-memory store for current agent statuses.
 * Listens to AgentStateChangedEvent from amsha-core.
 */
@ApplicationScoped
public class AgentStateStore {

    private final Map<String, StateEntry> currentStates = new ConcurrentHashMap<>();
    private final AtomicLong changeSequence = new AtomicLong();

    public void onAgentStateChanged(@Observes AgentStateChangedEvent event) {
        if (event.agentName() == null || "system".equalsIgnoreCase(event.agentName())) {
            return;
        }
        String normalizedAgent = event.agentName().contains(":")
                ? event.agentName().substring(event.agentName().lastIndexOf(':') + 1)
                : event.agentName();
        String role = normalizedAgent.toLowerCase(Locale.ROOT);
        String key = (event.sessionId() != null ? event.sessionId() : "") + "|" + role;
        currentStates.put(
                key,
                new StateEntry(
                        new AgentState(normalizedAgent, event.newStatus(), event.currentTask(), event.sessionId()),
                        changeSequence.incrementAndGet()));
    }

    public Collection<AgentState> getAll() {
        Map<String, StateEntry> byRole = new LinkedHashMap<>();
        for (StateEntry entry : currentStates.values()) {
            String role = entry.state().name().toLowerCase(Locale.ROOT);
            StateEntry previous = byRole.get(role);
            if (previous == null || entry.sequence() > previous.sequence()) {
                byRole.put(role, entry);
            }
        }
        return byRole.values().stream().map(StateEntry::state).toList();
    }

    public Collection<AgentState> getBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        return currentStates.entrySet().stream()
                .filter(e -> e.getKey().startsWith(sessionId + "|"))
                .map(Map.Entry::getValue)
                .map(StateEntry::state)
                .toList();
    }

    private record StateEntry(AgentState state, long sequence) {}

    public record AgentState(String name, String status, String currentTask, String sessionId) {}
}
