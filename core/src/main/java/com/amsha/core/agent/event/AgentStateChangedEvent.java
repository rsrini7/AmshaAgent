package com.amsha.core.agent.event;

/**
 * Fired by AgentStateMachineRunner when an agent's lifecycle state or task change.
 * Observed by UI resources to push SSE updates to the frontend.
 */
public record AgentStateChangedEvent(String sessionId, String agentName, String newStatus, String currentTask) {}
